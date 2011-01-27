/**
 * Copyright (c) 2010 Yahoo! Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. See accompanying LICENSE file.
 */
package org.apache.hadoop.http.authentication.web;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class <code>FileSystemEvictorCallback</code> is to write information down when eviction of ugi happens.
 *
 */
public class FileSystemEvictorCallback implements EvictorCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemEvictorCallback.class);

    @Override
    public void callback(final UserGroupInformation ugi) {
        try {
            FileSystem.closeAllForUGI(ugi);
            LOGGER.info("Closed all filesystems for user " + ugi.getShortUserName());
        }
        catch (Throwable t) {
            LOGGER.warn("Exception while closing filesystem for user " + ugi.getShortUserName(), t);
        }
    }
}
