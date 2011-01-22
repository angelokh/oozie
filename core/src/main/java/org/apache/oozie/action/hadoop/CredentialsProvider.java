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
package org.apache.oozie.action.hadoop;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.oozie.service.Services;
import org.apache.oozie.util.XLog;

public class CredentialsProvider {
    Credentials auth;
    String type;
    private static final String AUTH_KEY = "oozie.authentications.authenticator";
    private static final XLog LOG = XLog.getLog(CredentialsProvider.class);

    /**
     * @param type
     */
    public CredentialsProvider(String type) {
        this.type = type;
        this.auth = null;
        LOG.debug("Credentials Provider is created for Type: " + type);
    }

    /**
     * Create Credentials object
     *
     * @return Credentials object
     * @throws Exception
     */
    public Credentials createAuthenticator() throws Exception {
        Configuration conf;
        String type;
        String classname;
        conf = Services.get().getConf();
        if (conf.get(AUTH_KEY, "").trim().length() > 0) {
            for (String function : conf.getStrings(AUTH_KEY)) {
                function = Trim(function);
                LOG.debug("Creating Credentials class for : " + function);
                String[] str = function.split("=");
                if (str.length > 0) {
                    type = str[0];
                    classname = str[1];
                    LOG.debug("Creating Credentials type : '" + type + "', class Name : '" + classname + "'");
                    if (this.type.equalsIgnoreCase(str[0])) {
                        Class<?> klass = null;
                        try {
                            klass = Thread.currentThread().getContextClassLoader().loadClass(classname);
                        }
                        catch (ClassNotFoundException ex) {
                            LOG.warn("Exception while loading the class", ex);
                            throw ex;
                        }

                        auth = (Credentials) ReflectionUtils.newInstance(klass, null);
                    }
                }
            }
        }
        return auth;
    }

    /**
     * To trim string
     *
     * @param str
     * @return trim string
     */
    public String Trim(String str) {
        if (str != null) {
            str = str.replaceAll("\\n", "");
            str = str.replaceAll("\\t", "");
            str = str.trim();
        }
        return str;
    }
}
