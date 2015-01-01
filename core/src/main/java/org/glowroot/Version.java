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
package org.glowroot;

import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Manifests;

class Version {

    private static final Logger logger = LoggerFactory.getLogger(Version.class);

    private Version() {}

    static String getVersion() {
        Manifest manifest;
        try {
            manifest = Manifests.getManifest(Version.class);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return "unknown";
        }
        return getVersion(manifest);
    }

    @VisibleForTesting
    static String getVersion(@Nullable Manifest manifest) {
        if (manifest == null) {
            // manifest is missing when running ui testing and integration tests from inside IDE
            // so only log this at debug level
            logger.debug("could not locate META-INF/MANIFEST.MF file");
            return "unknown";
        }
        Attributes mainAttributes = manifest.getMainAttributes();
        String version = mainAttributes.getValue("Implementation-Version");
        if (version == null) {
            logger.warn("could not find Implementation-Version attribute in META-INF/MANIFEST.MF"
                    + " file");
            return "unknown";
        }
        if (version.endsWith("-SNAPSHOT")) {
            return getSnapshotVersion(version, mainAttributes);
        }
        return version;
    }

    private static String getSnapshotVersion(String version, Attributes mainAttributes) {
        StringBuilder snapshotVersion = new StringBuilder(version);
        String commit = mainAttributes.getValue("Build-Commit");
        if (commit != null && !commit.isEmpty()) {
            if (commit.length() == 40) {
                snapshotVersion.append(", commit ");
                snapshotVersion.append(commit.substring(0, 10));
            } else {
                logger.warn("invalid Build-Commit attribute in META-INF/MANIFEST.MF file,"
                        + " should be a 40 character git commit hash");
            }
        }
        String snapshotTimestamp = mainAttributes.getValue("Build-Time");
        if (snapshotTimestamp == null) {
            logger.warn("could not find Build-Time attribute in META-INF/MANIFEST.MF file");
            return snapshotVersion.toString();
        }
        snapshotVersion.append(", built at ");
        snapshotVersion.append(snapshotTimestamp);
        return snapshotVersion.toString();
    }
}
