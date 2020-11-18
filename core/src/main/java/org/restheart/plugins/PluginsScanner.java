/*-
 * ========================LICENSE_START=================================
 * restheart-core
 * %%
 * Copyright (C) 2014 - 2020 SoftInstigate
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * =========================LICENSE_END==================================
 */
package org.restheart.plugins;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import org.restheart.Bootstrapper;
import org.restheart.graal.NativeImageBuildTimeChecker;
import org.restheart.plugins.security.AuthMechanism;
import org.restheart.plugins.security.Authenticator;
import org.restheart.plugins.security.Authorizer;
import org.restheart.plugins.security.TokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * this class is configured to be initialized at build time by native-image
 * note: we cannot use logging in this class, otherwise native-image will fail
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class PluginsScanner {
    private static final String REGISTER_PLUGIN_CLASS_NAME = RegisterPlugin.class.getName();

    private static final String INITIALIZER_CLASS_NAME = Initializer.class.getName();
    private static final String AUTHMECHANISM_CLASS_NAME = AuthMechanism.class.getName();
    private static final String AUTHORIZER_CLASS_NAME = Authorizer.class.getName();
    private static final String TOKEN_MANAGER_CLASS_NAME = TokenManager.class.getName();
    private static final String AUTHENTICATOR_CLASS_NAME = Authenticator.class.getName();
    private static final String INTERCEPTOR_CLASS_NAME = Interceptor.class.getName();
    private static final String SERVICE_CLASS_NAME = Service.class.getName();

    static final ArrayList<PluginDescriptor> INITIALIZERS = new ArrayList<>();
    static final ArrayList<PluginDescriptor> AUTH_MECHANISMS = new ArrayList<>();
    static final ArrayList<PluginDescriptor> AUTHORIZERS = new ArrayList<>();
    static final ArrayList<PluginDescriptor> TOKEN_MANAGERS = new ArrayList<>();
    static final ArrayList<PluginDescriptor> AUTHENTICATORS = new ArrayList<>();
    static final ArrayList<PluginDescriptor> INTERCEPTORS = new ArrayList<>();
    static final ArrayList<PluginDescriptor> SERVICES = new ArrayList<>();

    public static final ArrayList<InjectionDescriptor> INJECTIONS = new ArrayList<>();

    static URL[] jars = null;

    // ClassGraph.scan() at class initialization time to support native image
    // generation with GraalVM
    // see https://github.com/SoftInstigate/classgraph-on-graalvm
    static {
        ClassGraph classGraph;

        if (NativeImageBuildTimeChecker.isBuildTime()) {
            System.out.println("*******************************************");
            System.out.println("we are at native image build time");
            System.out.println("*******************************************");
            classGraph = new ClassGraph().disableModuleScanning() // added for GraalVM
                    .disableDirScanning() // added for GraalVM
                    .disableNestedJarScanning() // added for GraalVM
                    .disableRuntimeInvisibleAnnotations() // added for GraalVM
                    .overrideClassLoaders(PluginsScanner.class.getClassLoader()) // added for GraalVM. Mandatory,
                                                                                 // otherwise build fails
                    .enableAnnotationInfo().enableMethodInfo().initializeLoadedClasses();
        } else {
            System.out.println("*******************************************");
            System.out.println("we are at run time");
            System.out.println("*******************************************");
            var rtcg = new RuntimeClassGraph();
            classGraph = rtcg.get();
            jars = rtcg.jars;
        }

        try (var scanResult = classGraph.scan(8)) {
            INITIALIZERS.addAll(collectPlugins(scanResult, INITIALIZER_CLASS_NAME));
            AUTH_MECHANISMS.addAll(collectPlugins(scanResult, AUTHMECHANISM_CLASS_NAME));
            AUTHORIZERS.addAll(collectPlugins(scanResult, AUTHORIZER_CLASS_NAME));
            TOKEN_MANAGERS.addAll(collectPlugins(scanResult, TOKEN_MANAGER_CLASS_NAME));
            AUTHENTICATORS.addAll(collectPlugins(scanResult, AUTHENTICATOR_CLASS_NAME));
            INTERCEPTORS.addAll(collectPlugins(scanResult, INTERCEPTOR_CLASS_NAME));
            SERVICES.addAll(collectPlugins(scanResult, SERVICE_CLASS_NAME));
        }

        System.out.println("*******************************************");

        System.out.println(PluginsScanner.SERVICES);

        System.out.println("*******************************************");
    }

    /**
     * @param type the class of the plugin , e.g. Initializer.class
     */
    private static ArrayList<PluginDescriptor> collectPlugins(ScanResult scanResult, String className) {
        var ret = new ArrayList<PluginDescriptor>();

        var registeredPlugins = scanResult.getClassesWithAnnotation(REGISTER_PLUGIN_CLASS_NAME);

        if (registeredPlugins == null || registeredPlugins.isEmpty()) {
            return ret;
        }

        ClassInfoList listOfType;

        if (className.equals(AUTHENTICATOR_CLASS_NAME)) {
            var tms = scanResult.getClassesImplementing(TOKEN_MANAGER_CLASS_NAME);

            listOfType = scanResult.getClassesImplementing(className).exclude(tms);
        } else {
            listOfType = scanResult.getClassesImplementing(className);
        }

        var plugins = registeredPlugins.intersect(listOfType);

        plugins.stream().forEachOrdered(plugin -> {
            ret.add(new PluginDescriptor(plugin.getName(),
            collectInjections(plugin)));
        });

        return ret;
    }

    private static ArrayList<InjectionDescriptor> collectInjections(ClassInfo pluginClassInfo) {
        var ret = new ArrayList<InjectionDescriptor>();

        ret.addAll(collectInjections(pluginClassInfo, InjectConfiguration.class));
        ret.addAll(collectInjections(pluginClassInfo, InjectPluginsRegistry.class));
        ret.addAll(collectInjections(pluginClassInfo, InjectMongoClient.class));

        return ret;
    }

    private static ArrayList<InjectionDescriptor> collectInjections(ClassInfo pluginClassInfo, Class injection) {
        var ret = new ArrayList<InjectionDescriptor>();

        var mil = pluginClassInfo.getDeclaredMethodInfo();

        for (var mi : mil) {
            if (mi.hasAnnotation(injection.getName())) {
                ret.add(new InjectionDescriptor(mi.getName(), injection));
            }
        }

        return ret;
    }

    static class RuntimeClassGraph {
        private static final Logger LOGGER = LoggerFactory.getLogger(PluginsFactory.class);

        private ClassGraph classGraph;

        URL[] jars = null;

        public RuntimeClassGraph() {
            var pdir = getPluginsDirectory();
            this.jars = findPluginsJars(pdir);

            if (jars != null && jars.length != 0) {
                this.classGraph = new ClassGraph().disableModuleScanning().disableDirScanning()
                        .disableNestedJarScanning().disableRuntimeInvisibleAnnotations()
                        .addClassLoader(new URLClassLoader((jars))).addClassLoader(ClassLoader.getSystemClassLoader())
                        .enableAnnotationInfo().enableMethodInfo().initializeLoadedClasses();
            } else {
                this.classGraph = new ClassGraph().disableModuleScanning().disableDirScanning()
                        .disableNestedJarScanning().disableRuntimeInvisibleAnnotations()
                        .addClassLoader(ClassLoader.getSystemClassLoader()).enableAnnotationInfo().enableMethodInfo()
                        .initializeLoadedClasses();
            }
        }

        public ClassGraph get() {
            return this.classGraph;
        }

        private Path getPluginsDirectory() {
            var pluginsDir = Bootstrapper.getConfiguration().getPluginsDirectory();

            if (pluginsDir == null) {
                return null;
            }

            if (pluginsDir.startsWith("/")) {
                return Paths.get(pluginsDir);
            } else {
                // this is to allow specifying the plugins directory path
                // relative to the jar (also working when running from classes)
                URL location = PluginsFactory.class.getProtectionDomain().getCodeSource().getLocation();

                File locationFile = new File(location.getPath());

                pluginsDir = locationFile.getParent() + File.separator + pluginsDir;

                return FileSystems.getDefault().getPath(pluginsDir);
            }
        }

        private URL[] findPluginsJars(Path pluginsDirectory) {
            if (pluginsDirectory == null) {
                return new URL[0];
            } else {
                checkPluginDirectory(pluginsDirectory);
            }

            var urls = new ArrayList<>();

            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(pluginsDirectory, "*.jar")) {
                for (Path path : directoryStream) {
                    var jar = path.toUri().toURL();

                    if (!Files.isReadable(path)) {
                        LOGGER.error("Plugin jar {} is not readable", jar);
                        throw new IllegalStateException("Plugin jar " + jar + " is not readable");
                    }

                    urls.add(jar);
                    LOGGER.info("Found plugin jar {}", jar);
                }
            } catch (IOException ex) {
                LOGGER.error("Cannot read jars in plugins directory {}",
                        Bootstrapper.getConfiguration().getPluginsDirectory(), ex.getMessage());
            }

            return urls.isEmpty() ? null : urls.toArray(new URL[urls.size()]);
        }

        private void checkPluginDirectory(Path pluginsDirectory) {
            if (!Files.exists(pluginsDirectory)) {
                LOGGER.error("Plugin directory {} does not exist", pluginsDirectory);
                throw new IllegalStateException("Plugins directory " + pluginsDirectory + " does not exist");
            }

            if (!Files.isReadable(pluginsDirectory)) {
                LOGGER.error("Plugin directory {} is not readable", pluginsDirectory);
                throw new IllegalStateException("Plugins directory " + pluginsDirectory + " is not readable");
            }
        }
    }
}

class PluginDescriptor {
    final String clazz;
    final ArrayList<InjectionDescriptor> injections;

    PluginDescriptor(String clazz, ArrayList<InjectionDescriptor> injections) {
        this.clazz = clazz;
        this.injections = injections;
    }

    @Override
    public String toString() {
        return "{ clazz:" + this.clazz + ", injections: " + this.injections + " }";
    }
}

class InjectionDescriptor {
    final String method;
    final Class injection;

    InjectionDescriptor(String method, Class injection) {
        this.method = method;
        this.injection = injection;
    }

    @Override
    public String toString() {
        return "{ method:" + this.method + ", injection: " + this.injection + " }";
    }
}
