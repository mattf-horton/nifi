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
package org.apache.nifi.nar;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.apache.nifi.util.FileUtils;
import org.apache.nifi.util.NiFiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public final class NarClassLoaders {

    public static final String FRAMEWORK_NAR_ID = "nifi-framework-nar";
    public static final String JETTY_NAR_ID = "nifi-jetty-bundle";

    private static final Logger logger = LoggerFactory.getLogger(NarClassLoaders.class);
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicReference<Map<String, ClassLoader>> extensionClassLoaders = new AtomicReference<>();
    private static final AtomicReference<Map<String, ClassLoader>> sideLoadedExtensionClassLoaders = new AtomicReference<>();
    private static final AtomicReference<ClassLoader> frameworkClassLoader = new AtomicReference<>();
    private static final AtomicReference<Map<String, ClassLoader>> narIdClassLoaders = new AtomicReference<>();

    /**
     * Loads the extensions class loaders from the specified working directory.
     * Loading is only performed during the initial invocation of load.
     * Subsequent attempts will be ignored.
     *
     *
     * @param properties properties object to initialize with
     * @throws java.io.IOException ioe
     * @throws java.lang.ClassNotFoundException cfne
     * @throws IllegalStateException if the class loaders have already been
     * created
     */
    public static void load(final NiFiProperties properties) throws IOException, ClassNotFoundException {
        if (initialized.getAndSet(true)) {
            throw new IllegalStateException("Extensions class loaders have already been loaded.");
        }

        // get the system classloader
        final ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

        // find all nar files and create class loaders for them.
        final Map<String, ClassLoader> extensionDirectoryClassLoaderLookup = new LinkedHashMap<>();
        final Map<String, ClassLoader> narIdClassLoaderLookup = new HashMap<>();

        final File frameworkWorkingDirectory = properties.getFrameworkWorkingDirectory();
        final File extensionsWorkingDirectory = properties.getExtensionsWorkingDirectory();

        // make sure the nar directory is there and accessible
        FileUtils.ensureDirectoryExistAndCanAccess(frameworkWorkingDirectory);
        FileUtils.ensureDirectoryExistAndCanAccess(extensionsWorkingDirectory);

        final List<File> narWorkingDirContents = new ArrayList<>();
        final File[] frameworkWorkingDirContents = frameworkWorkingDirectory.listFiles();
        if (frameworkWorkingDirContents != null) {
            narWorkingDirContents.addAll(Arrays.asList(frameworkWorkingDirContents));
        }
        final File[] extensionsWorkingDirContents = extensionsWorkingDirectory.listFiles();
        if (extensionsWorkingDirContents != null) {
            narWorkingDirContents.addAll(Arrays.asList(extensionsWorkingDirContents));
        }

        if (!narWorkingDirContents.isEmpty()) {
            final List<NarDetails> narDetails = new ArrayList<>();

            // load the nar details which includes and nar dependencies
            for (final File unpackedNar : narWorkingDirContents) {
                final NarDetails narDetail = getNarDetails(unpackedNar);

                // ensure the nar contained an identifier
                if (narDetail.getNarId() == null) {
                    logger.warn("No NAR Id found. Skipping: " + unpackedNar.getAbsolutePath());
                    continue;
                }

                // store the nar details
                narDetails.add(narDetail);
            }

            // attempt to locate the jetty nar
            ClassLoader jettyClassLoader = null;
            for (final Iterator<NarDetails> narDetailsIter = narDetails.iterator(); narDetailsIter.hasNext();) {
                final NarDetails narDetail = narDetailsIter.next();

                // look for the jetty nar
                if (JETTY_NAR_ID.equals(narDetail.getNarId())) {
                    // create the jetty classloader
                    jettyClassLoader = createNarClassLoader(narDetail.getNarWorkingDirectory(), systemClassLoader);

                    // remove the jetty nar since its already loaded
                    narIdClassLoaderLookup.put(narDetail.getNarId(), jettyClassLoader);
                    narDetailsIter.remove();
                    break;
                }
            }

            // ensure the jetty nar was found
            if (jettyClassLoader == null) {
                throw new IllegalStateException("Unable to locate Jetty bundle.");
            }

            int narCount;
            do {
                // record the number of nars to be loaded
                narCount = narDetails.size();

                // attempt to create each nar class loader
                for (final Iterator<NarDetails> narDetailsIter = narDetails.iterator(); narDetailsIter.hasNext();) {
                    final NarDetails narDetail = narDetailsIter.next();
                    final String narDependencies = narDetail.getNarDependencyId();

                    // see if this class loader is eligible for loading
                    ClassLoader narClassLoader = null;
                    if (narDependencies == null) {
                        narClassLoader = createNarClassLoader(narDetail.getNarWorkingDirectory(), jettyClassLoader);
                    } else if (narIdClassLoaderLookup.containsKey(narDetail.getNarDependencyId())) {
                        narClassLoader = createNarClassLoader(narDetail.getNarWorkingDirectory(), narIdClassLoaderLookup.get(narDetail.getNarDependencyId()));
                    }

                    // if we were able to create the nar class loader, store it and remove the details
                    if (narClassLoader != null) {
                        extensionDirectoryClassLoaderLookup.put(narDetail.getNarWorkingDirectory().getCanonicalPath(), narClassLoader);
                        narIdClassLoaderLookup.put(narDetail.getNarId(), narClassLoader);
                        narDetailsIter.remove();
                    }
                }

                // attempt to load more if some were successfully loaded this iteration
            } while (narCount != narDetails.size());

            // see if any nars couldn't be loaded
            for (final NarDetails narDetail : narDetails) {
                logger.warn(String.format("Unable to resolve required dependency '%s'. Skipping NAR %s", narDetail.getNarDependencyId(), narDetail.getNarWorkingDirectory().getAbsolutePath()));
            }
        }

        // set the framework class loader
        frameworkClassLoader.set(narIdClassLoaderLookup.get(FRAMEWORK_NAR_ID));

        // set the extensions class loader map
        extensionClassLoaders.set(new LinkedHashMap<>(extensionDirectoryClassLoaderLookup));

        // set nar classloaders for future reference
        narIdClassLoaders.set(new LinkedHashMap<>(narIdClassLoaderLookup));
    }

    public static boolean sideLoad(final File unpackedNar) throws IOException, ClassNotFoundException {
        final NarDetails narDetail = getNarDetails(unpackedNar);
     // ensure the nar contained an identifier
        if (narDetail.getNarId() == null) {
            logger.warn("No NAR Id found. Skipping: " + unpackedNar.getAbsolutePath());
            return false;
        }
        final String narDependencies = narDetail.getNarDependencyId();
        Map<String, ClassLoader> narIdClassLoaderLookup = narIdClassLoaders.get();
        // see if this class loader is eligible for loading
        ClassLoader narClassLoader = null;
        if (narDependencies == null) {
            narClassLoader = createNarClassLoader(narDetail.getNarWorkingDirectory(),
                    narIdClassLoaderLookup.get(JETTY_NAR_ID));
        } else if (narIdClassLoaderLookup.containsKey(narDetail.getNarDependencyId())) {
            narClassLoader = createNarClassLoader(narDetail.getNarWorkingDirectory(),
                    narIdClassLoaderLookup.get(narDetail.getNarDependencyId()));
        }

        // if we were able to create the nar class loader, store it and remove
        // the details
        if (narClassLoader != null) {
            HashMap<String, ClassLoader> extetensionDirectoryClassLoaderLookup=new HashMap<>();
            extetensionDirectoryClassLoaderLookup.put(narDetail.getNarWorkingDirectory().getCanonicalPath(), narClassLoader);
            extensionClassLoaders.accumulateAndGet(extetensionDirectoryClassLoaderLookup,
                    new MergeMapOperator<String, ClassLoader>());
            if (!sideLoadedExtensionClassLoaders.compareAndSet(null, extetensionDirectoryClassLoaderLookup)) {
            sideLoadedExtensionClassLoaders.accumulateAndGet(extetensionDirectoryClassLoaderLookup,
                    new MergeMapOperator<String, ClassLoader>());
            }
            HashMap<String, ClassLoader> narIdClassLoaderMap = new HashMap<>();
            narIdClassLoaderMap.put(narDetail.getNarId(), narClassLoader);
            narIdClassLoaders.accumulateAndGet(narIdClassLoaderMap, new MergeMapOperator<>());
        }
        return true;
    }

    /**
     * Creates a new NarClassLoader. The parentClassLoader may be null.
     *
     * @param narDirectory root directory of nar
     * @param parentClassLoader parent classloader of nar
     * @return the nar classloader
     * @throws IOException ioe
     * @throws ClassNotFoundException cfne
     */
    private static ClassLoader createNarClassLoader(final File narDirectory, final ClassLoader parentClassLoader) throws IOException, ClassNotFoundException {
        logger.debug("Loading NAR file: " + narDirectory.getAbsolutePath());
        final ClassLoader narClassLoader = new NarClassLoader(narDirectory, parentClassLoader);
        logger.info("Loaded NAR file: " + narDirectory.getAbsolutePath() + " as class loader " + narClassLoader);
        return narClassLoader;
    }

    /**
     * Loads the details for the specified NAR. The details will be extracted
     * from the manifest file.
     *
     * @param narDirectory the nar directory
     * @return details about the NAR
     * @throws IOException ioe
     */
    private static NarDetails getNarDetails(final File narDirectory) throws IOException {
        final NarDetails narDetails = new NarDetails();
        narDetails.setNarWorkingDirectory(narDirectory);

        final File manifestFile = new File(narDirectory, "META-INF/MANIFEST.MF");
        try (final FileInputStream fis = new FileInputStream(manifestFile)) {
            final Manifest manifest = new Manifest(fis);
            final Attributes attributes = manifest.getMainAttributes();

            // get the nar details
            narDetails.setNarId(attributes.getValue("Nar-Id"));
            narDetails.setNarDependencyId(attributes.getValue("Nar-Dependency-Id"));
        }

        return narDetails;
    }

    /**
     * @return the framework class loader
     *
     * @throws IllegalStateException if the frame class loader has not been
     * loaded
     */
    public static ClassLoader getFrameworkClassLoader() {
        if (!initialized.get()) {
            throw new IllegalStateException("Framework class loader has not been loaded.");
        }

        return frameworkClassLoader.get();
    }

    /**
     * @param extensionWorkingDirectory the directory
     * @return the class loader for the specified working directory. Returns
     * null when no class loader exists for the specified working directory
     * @throws IllegalStateException if the class loaders have not been loaded
     */
    public static ClassLoader getExtensionClassLoader(final File extensionWorkingDirectory) {
        if (!initialized.get()) {
            throw new IllegalStateException("Extensions class loaders have not been loaded.");
        }

        try {
            return extensionClassLoaders.get().get(extensionWorkingDirectory.getCanonicalPath());
        } catch (final IOException ioe) {
            return null;
        }
    }

    /**
     * @return the extension class loaders
     * @throws IllegalStateException if the class loaders have not been loaded
     */
    public static Set<ClassLoader> getExtensionClassLoaders() {
        if (!initialized.get()) {
            throw new IllegalStateException("Extensions class loaders have not been loaded.");
        }

        return new LinkedHashSet<>(extensionClassLoaders.get().values());
    }

    public static Set<ClassLoader> getSideLoadedExtensionClassLoaders() {
        if (!initialized.get()) {
            throw new IllegalStateException("Extensions class loaders have not been loaded.");
        }

        return new LinkedHashSet<>(sideLoadedExtensionClassLoaders.get().values());
    }


    private static final class MergeMapOperator<T, U> implements BinaryOperator<Map<T, U>> {
        @Override
        public Map<T, U> apply(Map<T, U> existing, Map<T, U> delta) {
            existing.putAll(delta);
            return existing;
        }
    }

    private static class NarDetails {

        private String narId;
        private String narDependencyId;
        private File narWorkingDirectory;

        public String getNarDependencyId() {
            return narDependencyId;
        }

        public void setNarDependencyId(String narDependencyId) {
            this.narDependencyId = narDependencyId;
        }

        public String getNarId() {
            return narId;
        }

        public void setNarId(String narId) {
            this.narId = narId;
        }

        public File getNarWorkingDirectory() {
            return narWorkingDirectory;
        }

        public void setNarWorkingDirectory(File narWorkingDirectory) {
            this.narWorkingDirectory = narWorkingDirectory;
        }
    }

    private NarClassLoaders() {
    }
}
