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

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;
import org.apache.commons.io.IOUtils;
import org.commonjava.storage.pathmapped.config.DefaultPathMappedStorageConfig;
import org.commonjava.storage.pathmapped.config.PathMappedStorageConfig;
import org.commonjava.storage.pathmapped.core.FileBasedPhysicalStore;
import org.commonjava.storage.pathmapped.pathdb.datastax.CassandraPathDB;
import org.commonjava.storage.pathmapped.spi.FileInfo;
import org.commonjava.storage.pathmapped.spi.PhysicalStore;
import org.commonjava.storage.pathmapped.util.ChecksumCalculator;
import org.commonjava.storage.pathmapped.util.PathMapUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class CassandraMigrator
{
    private static final String MAVEN_HOSTED = "maven:hosted:";

    private static CassandraMigrator migrator;

    private final CassandraPathDB pathDB;

    private final PhysicalStore physicalStore;

    private final IndyStoreBasedPathGenerator storePathGen;

    private final boolean dedup;

    private final ChecksumCalculator checksumCalculator;

    private final PreparedStatement preparedStoresIncrement;

    private final String keyspace = "indycache";

    private final String gaStorePattern = "^build-\\d+";

    private final Session session;

    private final String SCANNED_STORES = "scanned-stores";

    private final Set<String> scanned = Collections.synchronizedSet( new HashSet<>() );

    // @formatter:off
    private static String getSchemaCreateTable( String keyspace )
    {
        return "CREATE TABLE IF NOT EXISTS " + keyspace + ".ga ("
                        + "ga varchar,"
                        + "stores set<text>,"
                        + "PRIMARY KEY (ga)"
                        + ");";
    }
    // @formatter:on

    private CassandraMigrator( final PathMappedStorageConfig config, final String baseDir,
                               final boolean dedup, final String dedupAlgo ) throws MigrateException
    {
        this.pathDB = new CassandraPathDB( config );
        this.session = pathDB.getSession();
        session.execute( getSchemaCreateTable( keyspace ) );
        this.preparedStoresIncrement = session.prepare( "UPDATE " + keyspace + ".ga SET stores = stores + ? WHERE ga=?;" );
        this.storePathGen = new IndyStoreBasedPathGenerator( baseDir );
        this.physicalStore = new FileBasedPhysicalStore( new File( baseDir ) );
        this.dedup = dedup;
        if ( dedup )
        {
            try
            {
                checksumCalculator = new ChecksumCalculator( dedupAlgo );
            }
            catch ( NoSuchAlgorithmException e )
            {
                throw new MigrateException( String.format( "Can not init migrator. Error: %s", e.getMessage() ), e );
            }
        }
        else
        {
            checksumCalculator = null;
        }
    }

    public static CassandraMigrator getMigrator( final Map<String, Object> cassandraConfig, final String baseDir,
                                                 final boolean dedup, final String dedupAlgo )
            throws MigrateException
    {
        synchronized ( CassandraMigrator.class )
        {
            if ( migrator == null )
            {
                final PathMappedStorageConfig config = new DefaultPathMappedStorageConfig( cassandraConfig );
                migrator = new CassandraMigrator( config, baseDir, dedup, dedupAlgo );
            }
        }
        return migrator;

    }

    public void startUp()
    {

    }

    public void migrate( final String physicalFilePath )
            throws MigrateException
    {

        File file = Paths.get( physicalFilePath ).normalize().toFile();
        if ( !file.exists() || !file.isFile() )
        {
            throw new MigrateException( "Error: the physical path {} does not exists or is not a real file.",
                                        physicalFilePath );
        }

        String checksum = null;
        if ( dedup )
        {
            try
            {
                checksum = calculateChecksum( file );
            }
            catch ( IOException e )
            {
                throw new MigrateException(
                                String.format( "Error: Can not get file checksum for file of %s", physicalFilePath ), e );
            }
        }
        final String fileSystem = storePathGen.generateFileSystem( physicalFilePath );
        final String path = storePathGen.generatePath( physicalFilePath );
        final String storePath = storePathGen.generateStorePath( physicalFilePath );
        FileInfo fileInfo = physicalStore.getFileInfo( fileSystem, path );

        try
        {
            pathDB.insert( fileSystem, path, new Date(), null, fileInfo.getFileId(), file.length(), storePath, checksum );
            insertGa( fileSystem, path );
        }
        catch ( Exception e )
        {
            throw new MigrateException(
                    String.format( "Error: something wrong happened during update path db. Error: %s", e.getMessage() ),
                    e );
        }
    }

    private void insertGa( String fileSystem, String path )
    {
        if ( fileSystem.startsWith( MAVEN_HOSTED ) && path.endsWith( ".pom" ) )
        {
            String repoName = fileSystem.substring( MAVEN_HOSTED.length() );
            if ( gaStorePattern != null && repoName.matches( gaStorePattern ) )
            {
                update( getGaPath( path ), Collections.singleton( repoName ) );
                scanned.add( repoName );
            }
        }
    }

    private String getGaPath( String path )
    {
        String ret = null;
        String parentPath = PathMapUtils.getParentPath( path );
        if ( isNotBlank( parentPath ) )
        {
            Path ga = Paths.get( parentPath ).getParent();
            if ( ga != null )
            {
                ret = ga.toString();
                if ( ret.startsWith( "/" ) )
                {
                    ret = ret.substring( 1 ); // remove the leading '/'
                }
            }
        }
        return ret;
    }

    private void update( String ga, Set<String> set )
    {
        BoundStatement bound = preparedStoresIncrement.bind();
        bound.setSet( 0, set );
        bound.setString( 1, ga );
        session.execute( bound );
    }

    private String calculateChecksum( File file )
            throws IOException
    {
        if ( !file.exists() || !file.isFile() )
        {
            throw new IOException(
                    String.format( "Digest error: file not exists or not a regular file for file %s", file ) );
        }
        if ( checksumCalculator != null )
        {
            try (FileInputStream is = new FileInputStream( file ))
            {
                checksumCalculator.update( IOUtils.toByteArray( is ) );
            }
            return checksumCalculator.getDigestHex();
        }

        return null;
    }

    public void shutdown()
    {
        update( SCANNED_STORES, scanned );
        migrator = null;
        pathDB.close();
    }
}
