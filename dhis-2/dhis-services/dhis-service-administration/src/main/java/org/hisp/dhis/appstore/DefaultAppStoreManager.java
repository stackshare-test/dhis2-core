package org.hisp.dhis.appstore;

/*
 * Copyright (c) 2004-2018, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.hisp.dhis.appmanager.AppManager;
import org.hisp.dhis.appmanager.AppStatus;
import org.hisp.dhis.setting.SettingKey;
import org.hisp.dhis.setting.SystemSettingManager;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Lars Helge Overland
 */
@Service( "org.hisp.dhis.appstore.AppStoreManager" )
public class DefaultAppStoreManager
    implements AppStoreManager
{
    private final RestTemplate restTemplate;

    private final AppManager appManager;

    private final SystemSettingManager systemSettingManager;

    public DefaultAppStoreManager( RestTemplate restTemplate, AppManager appManager,
        SystemSettingManager systemSettingManager )
    {

        checkNotNull( restTemplate );
        checkNotNull( appManager );
        checkNotNull( systemSettingManager );

        this.restTemplate = restTemplate;
        this.appManager = appManager;
        this.systemSettingManager = systemSettingManager;
    }

    // -------------------------------------------------------------------------
    // AppStoreManager implementation
    // -------------------------------------------------------------------------

    @Override
    public AppStore getAppStore() {
        String appStoreIndexUrl = (String) systemSettingManager.getSystemSetting( SettingKey.APP_STORE_INDEX_URL );

        return restTemplate.getForObject( appStoreIndexUrl, AppStore.class );
    }

    @Override
    public AppStatus installAppFromAppStore( String id )
    {
        if ( id == null )
        {
            return AppStatus.NOT_FOUND;
        }

        try
        {
            Optional<WebAppVersion> webAppVersion = getWebAppVersion( id );

            if ( webAppVersion.isPresent() )
            {
                WebAppVersion version = webAppVersion.get();

                URL url = new URL( version.getDownloadUrl() );

                String filename = version.getFilename();

                return appManager.installApp( getFile( url ), filename );
            }

            return AppStatus.NOT_FOUND;
        }
        catch ( IOException ex )
        {
            throw new RuntimeException( "Failed to install app", ex );
        }
    }

    // -------------------------------------------------------------------------
    // Supportive methods
    // -------------------------------------------------------------------------

    private Optional<WebAppVersion> getWebAppVersion( String id )
    {
        AppStore appStore = getAppStore();

        for ( WebApp app : appStore.getApps() )
        {
            for ( WebAppVersion version : app.getVersions() )
            {
                if ( id.equals( version.getId() ) )
                {
                    return Optional.of( version );
                }
            }
        }

        return Optional.empty();
    }

    private static File getFile( URL url )
        throws IOException
    {
        URLConnection connection = url.openConnection();

        BufferedInputStream in = new BufferedInputStream( connection.getInputStream() );

        File tempFile = File.createTempFile( "dhis", null );

        tempFile.deleteOnExit();

        FileOutputStream out = new FileOutputStream( tempFile );

        IOUtils.copy( in, out );

        return tempFile;
    }
}
