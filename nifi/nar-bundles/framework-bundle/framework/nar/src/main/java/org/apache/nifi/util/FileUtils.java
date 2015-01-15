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
package org.apache.nifi.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.slf4j.Logger;

/**
 * A utility class containing a few useful static methods to do typical IO
 * operations.
 *
 * @author unattributed
 */
public class FileUtils {

    public static final long MILLIS_BETWEEN_ATTEMPTS = 50L;

    public static void ensureDirectoryExistAndCanAccess(final File dir) throws IOException {
        if (dir.exists() && !dir.isDirectory()) {
            throw new IOException(dir.getAbsolutePath() + " is not a directory");
        } else if (!dir.exists()) {
            final boolean made = dir.mkdirs();
            if (!made) {
                throw new IOException(dir.getAbsolutePath() + " could not be created");
            }
        }
        if (!(dir.canRead() && dir.canWrite())) {
            throw new IOException(dir.getAbsolutePath() + " directory does not have read/write privilege");
        }
    }

    /**
     * Deletes the given file. If the given file exists but could not be deleted
     * this will be printed as a warning to the given logger
     *
     * @param file
     * @param logger
     * @return
     */
    public static boolean deleteFile(final File file, final Logger logger) {
        return FileUtils.deleteFile(file, logger, 1);
    }

    /**
     * Deletes the given file. If the given file exists but could not be deleted
     * this will be printed as a warning to the given logger
     *
     * @param file
     * @param logger
     * @param attempts indicates how many times an attempt to delete should be
     * made
     * @return true if given file no longer exists
     */
    public static boolean deleteFile(final File file, final Logger logger, final int attempts) {
        if (file == null) {
            return false;
        }
        boolean isGone = false;
        try {
            if (file.exists()) {
                final int effectiveAttempts = Math.max(1, attempts);
                for (int i = 0; i < effectiveAttempts && !isGone; i++) {
                    isGone = file.delete() || !file.exists();
                    if (!isGone && (effectiveAttempts - i) > 1) {
                        FileUtils.sleepQuietly(MILLIS_BETWEEN_ATTEMPTS);
                    }
                }
                if (!isGone && logger != null) {
                    logger.warn("File appears to exist but unable to delete file: " + file.getAbsolutePath());
                }
            }
        } catch (final Throwable t) {
            if (logger != null) {
                logger.warn("Unable to delete file: '" + file.getAbsolutePath() + "' due to " + t);
            }
        }
        return isGone;
    }

    /**
     * Deletes all files (not directories..) in the given directory (non
     * recursive) that match the given filename filter. If any file cannot be
     * deleted then this is printed at warn to the given logger.
     *
     * @param directory
     * @param filter if null then no filter is used
     * @param logger
     */
    public static void deleteFilesInDir(final File directory, final FilenameFilter filter, final Logger logger) {
        FileUtils.deleteFilesInDir(directory, filter, logger, false);
    }

    /**
     * Deletes all files (not directories) in the given directory (recursive)
     * that match the given filename filter. If any file cannot be deleted then
     * this is printed at warn to the given logger.
     *
     * @param directory
     * @param filter if null then no filter is used
     * @param logger
     * @param recurse
     */
    public static void deleteFilesInDir(final File directory, final FilenameFilter filter, final Logger logger, final boolean recurse) {
        FileUtils.deleteFilesInDir(directory, filter, logger, recurse, false);
    }

    /**
     * Deletes all files (not directories) in the given directory (recursive)
     * that match the given filename filter. If any file cannot be deleted then
     * this is printed at warn to the given logger.
     *
     * @param directory
     * @param filter if null then no filter is used
     * @param logger
     * @param recurse will look for contents of sub directories.
     * @param deleteEmptyDirectories default is false; if true will delete
     * directories found that are empty
     */
    public static void deleteFilesInDir(final File directory, final FilenameFilter filter, final Logger logger, final boolean recurse, final boolean deleteEmptyDirectories) {
        // ensure the specified directory is actually a directory and that it exists
        if (null != directory && directory.isDirectory()) {
            final File ingestFiles[] = directory.listFiles();
            for (File ingestFile : ingestFiles) {
                boolean process = (filter == null) ? true : filter.accept(directory, ingestFile.getName());
                if (ingestFile.isFile() && process) {
                    FileUtils.deleteFile(ingestFile, logger, 3);
                }
                if (ingestFile.isDirectory() && recurse) {
                    FileUtils.deleteFilesInDir(ingestFile, filter, logger, recurse, deleteEmptyDirectories);
                    if (deleteEmptyDirectories && ingestFile.list().length == 0) {
                        FileUtils.deleteFile(ingestFile, logger, 3);
                    }
                }
            }
        }
    }

    /**
     * Deletes given files.
     *
     * @param files
     * @param recurse will recurse
     * @throws IOException
     */
    public static void deleteFiles(final Collection<File> files, final boolean recurse) throws IOException {
        for (final File file : files) {
            FileUtils.deleteFile(file, recurse);
        }
    }

    public static void deleteFile(final File file, final boolean recurse) throws IOException {
        if (file.isDirectory() && recurse) {
            FileUtils.deleteFiles(Arrays.asList(file.listFiles()), recurse);
        }
        //now delete the file itself regardless of whether it is plain file or a directory
        if (!FileUtils.deleteFile(file, null, 5)) {
            throw new IOException("Unable to delete " + file.getAbsolutePath());
        }
    }

    public static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ex) {
            /* do nothing */
        }
    }
}