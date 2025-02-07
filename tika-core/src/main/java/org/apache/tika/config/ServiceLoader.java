/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Internal utility class that Tika uses to look up service providers.
 *
 * @since Apache Tika 0.9
 */
public class ServiceLoader {

    /**
     * The default context class loader to use for all threads, or
     * <code>null</code> to automatically select the context class loader.
     */
    private static volatile ClassLoader contextClassLoader = null;

    /**
     * The dynamic set of services available in an OSGi environment.
     * Managed by the {@link TikaActivator} class and used as an additional
     * source of service instances in the {@link #loadServiceProviders(Class)}
     * method.
     */
    private static final Map<Object, Object> services =
            new HashMap<Object, Object>();

    /**
     * Returns the context class loader of the current thread. If such
     * a class loader is not available, then the loader of this class or
     * finally the system class loader is returned.
     *
     * @see <a href="https://issues.apache.org/jira/browse/TIKA-441">TIKA-441</a>
     * @return context class loader, or <code>null</code> if no loader
     *         is available
     */
    static ClassLoader getContextClassLoader() {
        ClassLoader loader = contextClassLoader;
        if (loader == null) {
            loader = ServiceLoader.class.getClassLoader();
        }
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        return loader;
    }

    /**
     * Sets the context class loader to use for all threads that access
     * this class. Used for example in an OSGi environment to avoid problems
     * with the default context class loader.
     *
     * @param loader default context class loader,
     *               or <code>null</code> to automatically pick the loader
     */
    public static void setContextClassLoader(ClassLoader loader) {
        contextClassLoader = loader;
    }

    static void addService(Object reference, Object service) {
        synchronized (services) {
            services.put(reference, service);
        }
    }

    static Object removeService(Object reference) {
        synchronized (services) {
            return services.remove(reference);
        }
    }

    private final ClassLoader loader;

    private final LoadErrorHandler handler;

    private final boolean dynamic;

    public ServiceLoader(
            ClassLoader loader, LoadErrorHandler handler, boolean dynamic) {
        this.loader = loader;
        this.handler = handler;
        this.dynamic = dynamic;
    }

    public ServiceLoader(ClassLoader loader, LoadErrorHandler handler) {
        this(loader, handler, false);
    }

    public ServiceLoader(ClassLoader loader) {
        this(loader, LoadErrorHandler.IGNORE);
    }

    public ServiceLoader() {
        this(getContextClassLoader(), LoadErrorHandler.IGNORE, true);
    }

    /**
     * Returns all the available service resources matching the
     *  given pattern, such as all instances of tika-mimetypes.xml 
     *  on the classpath, or all org.apache.tika.parser.Parser 
     *  service files.
     */
    public Enumeration<URL> findServiceResources(String filePattern) {
       try {
          Enumeration<URL> resources = loader.getResources(filePattern);
          return resources;
       } catch (IOException ignore) {
          // We couldn't get the list of service resource files
          List<URL> empty = Collections.emptyList();
          return Collections.enumeration( empty );
      }
    }

    /**
     * Returns all the available service providers of the given type.
     *
     * @param iface service provider interface
     * @return available service providers
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> loadServiceProviders(Class<T> iface) {
        List<T> providers = new ArrayList<T>();

        if (dynamic) {
            synchronized (services) {
                for (Object service : services.values()) {
                    if (iface.isAssignableFrom(service.getClass())) {
                        providers.add((T) service);
                    }
                }
            }
        }

        if (loader != null) {
            Set<String> names = new HashSet<String>();

            String serviceName = iface.getName();
            Enumeration<URL> resources =
                    findServiceResources("META-INF/services/" + serviceName);
            for (URL resource : Collections.list(resources)) {
                try {
                    names.addAll(getServiceClassNames(resource));
                } catch (IOException e) {
                    handler.handleLoadError(serviceName, e);
                }
            }

            for (String name : names) {
                try {
                    Class<?> klass = loader.loadClass(name);
                    if (iface.isAssignableFrom(klass)) {
                        providers.add((T) klass.newInstance());
                    }
                } catch (Throwable t) {
                    handler.handleLoadError(name, t);
                }
            }
        }

        return providers;
    }

    private static final Pattern COMMENT = Pattern.compile("#.*");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Set<String> getServiceClassNames(URL resource)
            throws IOException {
        Set<String> names = new HashSet<String>();
        InputStream stream = resource.openStream();
        try {
            BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            String line = reader.readLine();
            while (line != null) {
                line = COMMENT.matcher(line).replaceFirst("");
                line = WHITESPACE.matcher(line).replaceAll("");
                if (line.length() > 0) {
                    names.add(line);
                }
                line = reader.readLine();
            }
        } finally {
            stream.close();
        }
        return names;
    }

}
