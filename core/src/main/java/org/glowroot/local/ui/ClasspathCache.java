/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.Closer;
import com.google.common.io.Resources;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.weaving.AnalyzedWorld;
import org.glowroot.weaving.ClassNames;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ASM5;

// TODO need to remove items from classpathURIs and classNames when class loaders are no longer
// present, e.g. in wildfly after undeploying an application
class ClasspathCache {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathCache.class);

    private final AnalyzedWorld analyzedWorld;
    private final @Nullable Instrumentation instrumentation;

    // using sets of URIs because URLs have expensive equals and hashcode methods
    // see http://michaelscharf.blogspot.com/2006/11/javaneturlequals-and-hashcode-make.html
    @GuardedBy("this")
    private final Set<URI> classpathURIs = Sets.newHashSet();

    // using ImmutableMultimap because it is very space efficient
    // this is not updated often so trading space efficiency for copying the entire map on update
    @GuardedBy("this")
    private ImmutableMultimap<String, URI> classNames = ImmutableMultimap.of();

    ClasspathCache(AnalyzedWorld analyzedWorld, @Nullable Instrumentation instrumentation) {
        this.analyzedWorld = analyzedWorld;
        this.instrumentation = instrumentation;
    }

    // using synchronization instead of concurrent structures in this cache to conserve memory
    synchronized ImmutableList<String> getMatchingClassNames(String partialClassName, int limit) {
        // update cache before proceeding
        updateCache();
        String partialClassNameUpper = partialClassName.toUpperCase(Locale.ENGLISH);
        String prefixedPartialClassNameUpper1 = '.' + partialClassNameUpper;
        String prefixedPartialClassNameUpper2 = '$' + partialClassNameUpper;
        Set<String> fullMatchingClassNames = Sets.newLinkedHashSet();
        Set<String> matchingClassNames = Sets.newLinkedHashSet();
        // also check loaded classes, e.g. for groovy classes
        Iterator<String> i = classNames.keySet().iterator();
        if (instrumentation != null) {
            List<String> loadedClassNames = Lists.newArrayList();
            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                loadedClassNames.add(clazz.getName());
            }
            i = Iterators.concat(i, loadedClassNames.iterator());
        }
        while (i.hasNext()) {
            String className = i.next();
            String classNameUpper = className.toUpperCase(Locale.ENGLISH);
            boolean potentialFullMatch = classNameUpper.equals(partialClassNameUpper)
                    || classNameUpper.endsWith(prefixedPartialClassNameUpper1)
                    || classNameUpper.endsWith(prefixedPartialClassNameUpper2);
            if (matchingClassNames.size() == limit && !potentialFullMatch) {
                // once limit reached, only consider full matches
                continue;
            }
            if (fullMatchingClassNames.size() == limit) {
                break;
            }
            if (classNameUpper.startsWith(partialClassNameUpper)
                    || classNameUpper.contains(prefixedPartialClassNameUpper1)
                    || classNameUpper.contains(prefixedPartialClassNameUpper2)) {
                if (potentialFullMatch) {
                    fullMatchingClassNames.add(className);
                } else {
                    matchingClassNames.add(className);
                }
            }
        }
        return combineClassNamesWithLimit(fullMatchingClassNames, matchingClassNames, limit);
    }

    // using synchronization over concurrent structures in this cache to conserve memory
    synchronized ImmutableList<UiAnalyzedMethod> getAnalyzedMethods(String className) {
        // update cache before proceeding
        updateCache();
        Set<UiAnalyzedMethod> analyzedMethods = Sets.newHashSet();
        Collection<URI> uris = classNames.get(className);
        for (URI uri : uris) {
            try {
                analyzedMethods.addAll(getAnalyzedMethods(uri));
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        if (instrumentation != null) {
            // also check loaded classes, e.g. for groovy classes
            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                if (clazz.getName().equals(className)) {
                    analyzedMethods.addAll(getAnalyzedMethods(clazz));
                }
            }
        }
        return ImmutableList.copyOf(analyzedMethods);
    }

    // using synchronization over concurrent structures in this cache to conserve memory
    synchronized void updateCache() {
        Multimap<String, URI> newClassNames = HashMultimap.create();
        for (ClassLoader loader : getKnownClassLoaders()) {
            updateCache(loader, newClassNames);
        }
        updateCacheWithBootstrapClasses(newClassNames);
        if (!newClassNames.isEmpty()) {
            Multimap<String, URI> newMap =
                    TreeMultimap.create(String.CASE_INSENSITIVE_ORDER, Ordering.natural());
            newMap.putAll(classNames);
            newMap.putAll(newClassNames);
            classNames = ImmutableMultimap.copyOf(newMap);
        }
    }

    private ImmutableList<String> combineClassNamesWithLimit(Set<String> fullMatchingClassNames,
            Set<String> matchingClassNames, int limit) {
        if (fullMatchingClassNames.size() < limit) {
            int space = limit - fullMatchingClassNames.size();
            int numToAdd = Math.min(space, matchingClassNames.size());
            fullMatchingClassNames.addAll(
                    ImmutableList.copyOf(Iterables.limit(matchingClassNames, numToAdd)));
        }
        return ImmutableList.copyOf(fullMatchingClassNames);
    }

    private void updateCacheWithBootstrapClasses(Multimap<String, URI> newClassNames) {
        String bootClassPath = System.getProperty("sun.boot.class.path");
        if (bootClassPath == null) {
            return;
        }
        for (String path : Splitter.on(File.pathSeparatorChar).split(bootClassPath)) {
            URI uri = new File(path).toURI();
            if (!classpathURIs.contains(uri)) {
                loadClassNames(uri, newClassNames);
                classpathURIs.add(uri);
            }
        }
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(URI uri) throws IOException {
        AnalyzingClassVisitor cv = new AnalyzingClassVisitor();
        byte[] bytes = Resources.toByteArray(uri.toURL());
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, 0);
        return cv.getAnalyzedMethods();
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(Class<?> clazz) {
        List<UiAnalyzedMethod> analyzedMethods = Lists.newArrayList();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                // don't add synthetic methods to the analyzed model
                continue;
            }
            ImmutableUiAnalyzedMethod.Builder builder = ImmutableUiAnalyzedMethod.builder();
            builder.name(method.getName());
            for (Class<?> parameterType : method.getParameterTypes()) {
                // Class.getName() for arrays returns internal notation (e.g. "[B" for byte array)
                // so using Type.getType().getClassName() instead
                builder.addParameterTypes(Type.getType(parameterType).getClassName());
            }
            // Class.getName() for arrays returns internal notation (e.g. "[B" for byte array)
            // so using Type.getType().getClassName() instead
            builder.returnType(Type.getType(method.getReturnType()).getClassName());
            builder.modifiers(method.getModifiers());
            for (Class<?> exceptionType : method.getExceptionTypes()) {
                builder.addExceptions(exceptionType.getName());
            }
            analyzedMethods.add(builder.build());
        }
        return analyzedMethods;
    }

    private void updateCache(ClassLoader loader, Multimap<String, URI> newClassNames) {
        List<URL> urls = getURLs(loader);
        List<URI> uris = Lists.newArrayList();
        for (URL url : urls) {
            if (url.getProtocol().equals("vfs")) {
                try {
                    uris.add(getFileFromJBossVfsURL(url, loader).toURI());
                } catch (ClassNotFoundException e) {
                    logger.warn(e.getMessage(), e);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                } catch (ReflectiveException e) {
                    logger.warn(e.getMessage(), e);
                }
            } else {
                try {
                    uris.add(url.toURI());
                } catch (URISyntaxException e) {
                    // log exception at debug level
                    logger.debug(e.getMessage(), e);
                }
            }
        }
        for (URI uri : uris) {
            if (!classpathURIs.contains(uri)) {
                loadClassNames(uri, newClassNames);
                classpathURIs.add(uri);
            }
        }
    }

    private List<URL> getURLs(ClassLoader loader) {
        if (loader instanceof URLClassLoader) {
            try {
                return Lists.newArrayList(((URLClassLoader) loader).getURLs());
            } catch (Exception e) {
                // tomcat WebappClassLoader.getURLs() throws NullPointerException after stop() has
                // been called on the WebappClassLoader (this happens, for example, after a webapp
                // fails to load)
                //
                // log exception at debug level
                logger.debug(e.getMessage(), e);
                return ImmutableList.of();
            }
        }
        // special case for jboss/wildfly
        try {
            return Collections.list(loader.getResources("/"));
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    private List<ClassLoader> getKnownClassLoaders() {
        List<ClassLoader> loaders = analyzedWorld.getClassLoaders();
        if (loaders.isEmpty()) {
            // this is needed for testing the UI outside of javaagent
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            if (systemClassLoader == null) {
                return ImmutableList.of();
            } else {
                return ImmutableList.of(systemClassLoader);
            }
        }
        return loaders;
    }

    private static void loadClassNames(URI uri, Multimap<String, URI> newClassNames) {
        try {
            if (uri.getScheme().equals("file")) {
                File file = new File(uri);
                if (file.isDirectory()) {
                    loadClassNamesFromDirectory(file, "", newClassNames);
                } else if (file.exists() && file.getName().endsWith(".jar")) {
                    loadClassNamesFromJarFile(uri, newClassNames);
                }
            } else if (uri.getPath().endsWith(".jar")) {
                // try to load jar from non-file uri
                loadClassNamesFromJarFile(uri, newClassNames);
            }
        } catch (IOException e) {
            logger.debug("error reading classes from uri: {}", uri, e);
        }
    }

    private static void loadClassNamesFromDirectory(File dir, String prefix,
            Multimap<String, URI> newClassNames) throws MalformedURLException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".class")) {
                URI fileUri = new File(dir, name).toURI();
                String className = prefix + name.substring(0, name.lastIndexOf('.'));
                newClassNames.put(className, fileUri);
            } else if (file.isDirectory()) {
                loadClassNamesFromDirectory(file, prefix + name + ".", newClassNames);
            }
        }
    }

    private static void loadClassNamesFromJarFile(URI jarUri, Multimap<String, URI> newClassNames)
            throws IOException {
        Closer closer = Closer.create();
        InputStream s = jarUri.toURL().openStream();
        JarInputStream jarIn = closer.register(new JarInputStream(s));
        try {
            loadClassNamesFromManifestClassPath(jarIn, jarUri, newClassNames);
            loadClassNamesFromJarInputStream(jarIn, jarUri, newClassNames);
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    private static void loadClassNamesFromManifestClassPath(JarInputStream jarIn, URI jarUri,
            Multimap<String, URI> newClassNames) {
        Manifest manifest = jarIn.getManifest();
        if (manifest == null) {
            return;
        }
        String classpath = manifest.getMainAttributes().getValue("Class-Path");
        if (classpath == null) {
            return;
        }
        for (String path : Splitter.on(' ').omitEmptyStrings().split(classpath)) {
            URI uri = jarUri.resolve(path);
            loadClassNames(uri, newClassNames);
        }
    }

    private static void loadClassNamesFromJarInputStream(JarInputStream jarIn,
            URI jarUri, Multimap<String, URI> newClassNames) throws IOException {
        JarEntry jarEntry;
        while ((jarEntry = jarIn.getNextJarEntry()) != null) {
            if (jarEntry.isDirectory()) {
                continue;
            }
            String name = jarEntry.getName();
            if (!name.endsWith(".class")) {
                continue;
            }
            String className = name.substring(0, name.lastIndexOf('.')).replace('/', '.');
            // TODO test if this works with jar loaded over http protocol
            String path = jarUri.getPath();
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            try {
                URI fileURI = new URI("jar", jarUri.getScheme() + ":" + path + "!/" + name, "");
                newClassNames.put(className, fileURI);
            } catch (URISyntaxException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private static File getFileFromJBossVfsURL(URL url, ClassLoader loader) throws IOException,
            ClassNotFoundException, ReflectiveException {
        Object virtualFile = url.openConnection().getContent();
        Class<?> virtualFileClass = loader.loadClass("org.jboss.vfs.VirtualFile");
        Method getPhysicalFileMethod = Reflections.getMethod(virtualFileClass, "getPhysicalFile");
        Method getNameMethod = Reflections.getMethod(virtualFileClass, "getName");
        File physicalFile = (File) Reflections.invoke(getPhysicalFileMethod, virtualFile);
        checkNotNull(physicalFile, "org.jboss.vfs.VirtualFile.getPhysicalFile() returned null");
        String name = (String) Reflections.invoke(getNameMethod, virtualFile);
        checkNotNull(name, "org.jboss.vfs.VirtualFile.getName() returned null");
        return new File(physicalFile.getParentFile(), name);
    }

    private static class AnalyzingClassVisitor extends ClassVisitor {

        private final List<UiAnalyzedMethod> analyzedMethods = Lists.newArrayList();

        private AnalyzingClassVisitor() {
            super(ASM5);
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/[] exceptions) {
            if ((access & ACC_SYNTHETIC) != 0) {
                // don't add synthetic methods to the analyzed model
                return null;
            }
            ImmutableUiAnalyzedMethod.Builder builder = ImmutableUiAnalyzedMethod.builder();
            builder.name(name);
            for (Type parameterType : Type.getArgumentTypes(desc)) {
                builder.addParameterTypes(parameterType.getClassName());
            }
            builder.returnType(Type.getReturnType(desc).getClassName());
            builder.modifiers(access);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    builder.addExceptions(ClassNames.fromInternalName(exception));
                }
            }
            analyzedMethods.add(builder.build());
            return null;
        }

        private List<UiAnalyzedMethod> getAnalyzedMethods() {
            return analyzedMethods;
        }
    }
}
