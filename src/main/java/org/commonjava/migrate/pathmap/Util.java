/**
 * Copyright (C) 2013~2019 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.migrate.pathmap;

import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Util
{
    static final String DEFAULT_BASE_DIR = "/opt/indy/var/lib/indy/storage";

    static final String DEFAULT_WORK_DIR = "./";

    static final int DEFAULT_THREADS_NUM = 1;

    static final int DEFAULT_BATCH_SIZE = 100000;

    static final int DEFAULT_FAILED_BATCH_SIZE = 10000;

    static final String TODO_FILES_DIR = "todo";

    static final String PROCESSED_FILES_DIR = "processed";

    static final String FAILED_PATHS_FILE = "failed_paths";

    static final String STATUS_FILE = "scan_final";

    static final String GA_CACHE_DUMP = "ga_cache_dump";

    static final String PROGRESS_FILE = "migrate_progress";

    static final String CMD_SCAN = "scan";

    static final String CMD_MIGRATE = "migrate";

    static void prepareWorkingDir( final String workDir )
            throws IOException
    {
        Path todoDir = Paths.get( workDir, TODO_FILES_DIR );
        if ( todoDir.toFile().exists() )
        {
            printInfo( "todo folder is not empty, will clean it first." );
            FileUtils.forceDelete( todoDir.toFile() );
        }
        Files.createDirectories( todoDir );
        Path processedDir = Paths.get( workDir, PROCESSED_FILES_DIR );
        if ( processedDir.toFile().exists() )
        {
            printInfo( "processed folder is not empty, will clean it first." );
            FileUtils.forceDelete( processedDir.toFile() );
        }
        Files.createDirectories( processedDir );
    }

    static void printInfo( final String message )
    {
        System.out.println( message );
    }

    static void newLines( final int lines )
    {
        for ( int i = 0; i < lines; i++ )
        {
            System.out.println();
        }
    }

    static void newLine()
    {
        newLines( 1 );
    }

    static Map<Integer, List<Path>> slicePathsByMod( final List<Path> totalPaths, final int mod )
    {
        Map<Integer, List<Path>> slices = new HashMap<>( mod );
        for ( int i = 0; i < totalPaths.size(); i++ )
        {
            List<Path> repoPathSlice = slices.computeIfAbsent( (i % mod), ArrayList::new );
            repoPathSlice.add( totalPaths.get( i ) );
        }
        return slices;
    }

}
