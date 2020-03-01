/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.restheart.utils.FileUtils;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class Version {
    private final String version;
    private final Instant buildTime;

    private Version() {
        this.version = Version.class.getPackage().getImplementationVersion() == null
                ? null
                : Version.class.getPackage().getImplementationVersion();

        this.buildTime = extractBuildTime();
    }

    /**
     *
     * @return
     */
    public static Version getInstance() {
        return VersionHolder.INSTANCE;
    }

    private static class VersionHolder {
        private static final Version INSTANCE = new Version();
    }

    /**
     * the RESTHEart version is read from the JAR's MANIFEST.MF file, which is
     * automatically generated by the Maven build process
     *
     * @return the version of RESTHeart or null if it is not packaged
     */
    public String getVersion() {
        return version;
    }

    /**
     * the RESTHEart build time is read from the JAR's MANIFEST.MF file, which
     * is automatically generated by the Maven build process
     *
     * @return the build time defined in the MANIFEST.MF file, or now if it is
     * not packaged
     */
    public Instant getBuildTime() {
        return buildTime;
    }

    /**
     *
     * @return the build time defined in the MANIFEST.MF file, or now if not
     * present
     */
    private Instant extractBuildTime() {
        final Set<Map.Entry<Object, Object>> MANIFEST_ENTRIES = FileUtils.findManifestInfo();

        return MANIFEST_ENTRIES == null
                ? Instant.now()
                : MANIFEST_ENTRIES
                        .stream()
                        .filter(e -> e.getKey().toString().equals("Build-Time"))
                        .map(e -> (String) e.getValue())
                        .filter(d -> d != null)
                        .map(d -> {
                            try {
                                return Instant.parse(d);
                            } catch (Throwable t) {
                                return Instant.now();
                            }
                        })
                        .findFirst()
                        .orElse(Instant.now());
    }
}
