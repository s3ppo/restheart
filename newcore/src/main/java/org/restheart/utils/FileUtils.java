/*
 * RESTHeart Security
 *
 * Copyright (C) SoftInstigate Srl
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
package org.restheart.utils;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheNotFoundException;
import static com.sun.akuma.CLibrary.LIBC;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.restheart.ConfigurationException;
import org.restheart.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class FileUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileUtils.class);

    private static final Path DEFAULT_PID_DIR = new File("/var/run").toPath();
    private static final Path TMP_DIR = new File(System.getProperty("java.io.tmpdir")).toPath();

    public static Path getFileAbsolutePath(String path) {
        if (path == null) {
            return null;
        }

        return FileSystems.getDefault().getPath(path).toAbsolutePath();
    }

    public static int getFileAbsolutePathHash(Path confFilePath, Path propFilePath) {
        if (confFilePath == null) {
            return 0;
        }

        return Objects.hash(confFilePath, propFilePath);
    }

    public static Configuration getConfiguration(String[] args) throws ConfigurationException {
        return getConfiguration(getConfigurationFilePath(args),
                getPropertiesFilePath(args),
                false);
    }

    public static Configuration getConfiguration(String[] args, boolean silent) throws ConfigurationException {
        return getConfiguration(
                getConfigurationFilePath(args),
                getPropertiesFilePath(args),
                silent);
    }

    public static Configuration getConfiguration(Path configurationFilePath, Path propsFilePath, boolean silent) throws ConfigurationException {
        if (configurationFilePath != null) {
            if (propsFilePath != null) {
                final Properties p = new Properties();
                try (InputStreamReader reader = new InputStreamReader(
                        new FileInputStream(propsFilePath.toFile()), "UTF-8")) {
                    p.load(reader);
                } catch (FileNotFoundException fnfe) {
                    throw new ConfigurationException("Properties file not found: " + propsFilePath);
                } catch (UnsupportedEncodingException uec) {
                    throw new ConfigurationException("Unsupported encoding", uec);
                } catch (IOException iex) {
                    throw new ConfigurationException("Error reading properties file: " + propsFilePath, iex);
                }

                final StringWriter writer = new StringWriter();
                try (BufferedReader reader = new BufferedReader(new FileReader(configurationFilePath.toFile()))) {
                    Mustache m = new DefaultMustacheFactory().compile(reader, "configuration-file");
                    m.execute(writer, p);
                    writer.flush();
                } catch (MustacheNotFoundException ex) {
                    throw new ConfigurationException("Configuration file not found: " + configurationFilePath);
                } catch (IOException iex) {
                    throw new ConfigurationException("Error reading configuration file: " + configurationFilePath, iex);
                }

                Map<String, Object> obj = new Yaml().load(writer.toString());
                return new Configuration(obj, silent);
            } else {
                return new Configuration(configurationFilePath, silent);
            }
        } else {
            return new Configuration();
        }
    }

    public static Path getConfigurationFilePath(String[] args) {
        if (args != null) {
            for (String arg : args) {
                if (!arg.startsWith("-")) {
                    return getFileAbsolutePath(arg);
                }
            }
        }

        return null;
    }
    
    public static Path getPropertiesFilePath(String[] args) {
        if (args != null) {
            var _args = Arrays.asList(args);
            
            var opt = _args.indexOf("-e");
            
            return opt < 0
                    ? null
                    : _args.size() <= opt+1 
                    ? null
                    : getFileAbsolutePath(_args.get(opt+1));
        }

        return null;
    }

    public static Path getTmpDirPath() {
        return TMP_DIR;
    }

    public static Path getPidFilePath(int configurationFileHash) {
        if (OSChecker.isWindows()) {
            return null;
        }

        if (Files.isWritable(DEFAULT_PID_DIR)) {
            return DEFAULT_PID_DIR.resolve("restheart-security-" + configurationFileHash + ".pid");
        } else {
            return TMP_DIR.resolve("restheart-security-" + configurationFileHash + ".pid");
        }
    }

    public static void createPidFile(Path pidFile) {
        if (OSChecker.isWindows()) {
            LOGGER.warn("this method is not supported on Windows.");
            throw new IllegalStateException("createPidFile() is not supported on Windows.");
        }
        try (FileWriter fw = new FileWriter(pidFile.toFile())) {
            fw.write(String.valueOf(LIBC.getpid()));
        } catch (IOException e) {
            LOGGER.warn("error writing pid file", e);
        }
    }

    public static int getPidFromFile(Path pidFile) {
        try {
            try (BufferedReader br = new BufferedReader(new FileReader(pidFile.toFile()))) {
                String line = br.readLine();

                return Integer.parseInt(line);
            }
        } catch (FileNotFoundException fne) {
            LOGGER.debug("pid file not found", fne);
            return -1;
        } catch (IOException e) {
            LOGGER.debug("error reading the pid file", e);
            return -2;
        } catch (NumberFormatException e) {
            LOGGER.debug("unexpected content in pid file", e);
            return -3;
        }
    }

    public static Set<Entry<Object, Object>> findManifestInfo() {
        Set<Entry<Object, Object>> result = null;
        try {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                    .getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                URL manifestUrl = resources.nextElement();
                Manifest manifest = new Manifest(manifestUrl.openStream());
                Attributes mainAttributes = manifest.getMainAttributes();
                String implementationTitle = mainAttributes.getValue("Implementation-Title");
                if (implementationTitle != null && implementationTitle.toLowerCase().startsWith("restheart")) {
                    result = mainAttributes.entrySet();
                    break;
                }
            }
        } catch (IOException e) {
            LOGGER.warn(e.getMessage());
        }
        return result;
    }

    private FileUtils() {
    }
}
