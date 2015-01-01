/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DataDir {

    private static final Logger logger = LoggerFactory.getLogger(DataDir.class);

    private DataDir() {}

    public static File getDataDir(Map<String, String> properties, @Nullable File glowrootJarFile) {
        String dataDirPath = properties.get("data.dir");
        // empty check to support parameterized script, e.g. -Dglowroot.data.dir=${somevar}
        if (Strings.isNullOrEmpty(dataDirPath)) {
            File baseDir = getBaseDir(glowrootJarFile);
            return baseDir == null ? new File(".") : baseDir;
        }
        File dataDir = new File(dataDirPath);
        File baseDir = null;
        if (!dataDir.isAbsolute()) {
            // resolve path relative to base dir instead of process current dir
            baseDir = getBaseDir(glowrootJarFile);
            dataDir = baseDir == null ? new File(dataDirPath) : new File(baseDir, dataDirPath);
        }
        try {
            Files.createParentDirs(dataDir);
            return dataDir;
        } catch (IOException e) {
            if (baseDir == null) {
                baseDir = getBaseDir(glowrootJarFile);
                if (baseDir == null) {
                    baseDir = new File(".");
                }
            }
            logger.warn("error creating data directory: {} (using directory {} instead)",
                    dataDir.getAbsolutePath(), baseDir.getAbsolutePath(), e);
            return baseDir;
        }
    }

    private static @Nullable File getBaseDir(@Nullable File glowrootJarFile) {
        if (glowrootJarFile == null) {
            logWarning();
            return null;
        }
        return glowrootJarFile.getParentFile();
    }

    private static void logWarning() {
        // warning is logged lazily (instead of in static initializer) so that unit tests
        // have a chance to pass in absolute data dir path and bypass this warning
        logger.warn("could not determine location of glowroot.jar, using process current"
                + " directory as the data directory");
    }
}
