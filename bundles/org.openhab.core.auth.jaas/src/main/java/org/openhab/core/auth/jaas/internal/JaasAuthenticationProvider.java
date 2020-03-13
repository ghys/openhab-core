/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.auth.jaas.internal;

import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.openhab.core.auth.Authentication;
import org.openhab.core.auth.AuthenticationException;
import org.openhab.core.auth.AuthenticationProvider;
import org.openhab.core.auth.Credentials;
import org.openhab.core.auth.GenericUser;
import org.openhab.core.auth.UsernamePasswordCredentials;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;

/**
 * Implementation of authentication provider which is backed by JAAS realm.
 *
 * Real authentication logic is embedded in login modules implemented by 3rd party, this code is just for bridging it to
 * smarthome platform.
 *
 * @author Łukasz Dywicki - Initial contribution
 * @author Kai Kreuzer - Removed ManagedService and used DS configuration instead
 */
@Component(configurationPid = "org.openhab.jaas")
public class JaasAuthenticationProvider implements AuthenticationProvider {
    private final String DEFAULT_REALM = "openhab";

    private String realmName;

    @Override
    public Authentication authenticate(final Credentials credentials) throws AuthenticationException {
        if (realmName == null) { // configuration is not yet ready or set
            throw new AuthenticationException("Empty realm");
        }

        if (!(credentials instanceof UsernamePasswordCredentials)) {
            throw new AuthenticationException("Unsupported credentials passed to provider.");
        }

        UsernamePasswordCredentials userCredentials = (UsernamePasswordCredentials) credentials;
        final String name = userCredentials.getUsername();
        final char[] password = userCredentials.getPassword().toCharArray();

        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {

            Principal userPrincipal = new GenericUser(name);
            Subject subject = new Subject(true, Set.of(userPrincipal), Collections.emptySet(), Set.of(userCredentials));

            Thread.currentThread().setContextClassLoader(ManagedLoginModule.class.getClassLoader());
            LoginContext loginContext = new LoginContext(realmName, subject, new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    for (Callback callback : callbacks) {
                        if (callback instanceof PasswordCallback) {
                            ((PasswordCallback) callback).setPassword(password);
                        } else if (callback instanceof NameCallback) {
                            ((NameCallback) callback).setName(name);
                        } else {
                            throw new UnsupportedCallbackException(callback);
                        }
                    }
                }
            }, new ManagedLoginConfiguration());
            loginContext.login();

            return getAuthentication(name, loginContext.getSubject());
        } catch (LoginException e) {
            throw new AuthenticationException(e.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    private Authentication getAuthentication(String name, Subject subject) {
        return new Authentication(name, getRoles(subject.getPrincipals()));
    }

    private String[] getRoles(Set<Principal> principals) {
        String[] roles = new String[principals.size()];
        int i = 0;
        for (Principal principal : principals) {
            roles[i++] = principal.getName();
        }
        return roles;
    }

    @Activate
    protected void activate(Map<String, Object> properties) {
        modified(properties);
    }

    @Deactivate
    protected void deactivate(Map<String, Object> properties) {
    }

    @Modified
    protected void modified(Map<String, Object> properties) {
        if (properties == null) {
            realmName = DEFAULT_REALM; // null;
            return;
        }

        Object propertyValue = properties.get("realmName");
        if (propertyValue != null) {
            if (propertyValue instanceof String) {
                realmName = (String) propertyValue;
            } else {
                realmName = propertyValue.toString();
            }
        } else {
            // value could be unset, we should reset it value
            realmName = DEFAULT_REALM; // null;
        }
    }

    @Override
    public boolean supports(Class<? extends Credentials> type) {
        return UsernamePasswordCredentials.class.isAssignableFrom(type);
    }
}
