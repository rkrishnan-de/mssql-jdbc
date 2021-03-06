/*
 * Microsoft JDBC Driver for SQL Server Copyright(c) Microsoft Corporation All rights reserved. This program is made
 * available under the terms of the MIT License. See the LICENSE file in the project root for more information.
 */

package com.microsoft.sqlserver.jdbc;

import java.io.IOException;
import java.net.MalformedURLException;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import javax.security.auth.kerberos.KerberosPrincipal;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IClientCredential;
import com.microsoft.aad.msal4j.IntegratedWindowsAuthenticationParameters;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.UserNamePasswordParameters;
import com.microsoft.sqlserver.jdbc.SQLServerConnection.ActiveDirectoryAuthentication;
import com.microsoft.sqlserver.jdbc.SQLServerConnection.SqlFedAuthInfo;


class SQLServerMSAL4JUtils {

    static final private java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger("com.microsoft.sqlserver.jdbc.SQLServerMSAL4JUtils");

    static SqlFedAuthToken getSqlFedAuthToken(SqlFedAuthInfo fedAuthInfo, String user, String password,
            String authenticationString) throws SQLServerException {
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        try {
            final PublicClientApplication clientApplication = PublicClientApplication
                    .builder(ActiveDirectoryAuthentication.JDBC_FEDAUTH_CLIENT_ID).executorService(executorService)
                    .authority(fedAuthInfo.stsurl).build();
            final CompletableFuture<IAuthenticationResult> future = clientApplication
                    .acquireToken(UserNamePasswordParameters
                            .builder(Collections.singleton(fedAuthInfo.spn + "/.default"), user, password.toCharArray())
                            .build());

            final IAuthenticationResult authenticationResult = future.get();
            return new SqlFedAuthToken(authenticationResult.accessToken(), authenticationResult.expiresOnDate());
        } catch (MalformedURLException | InterruptedException e) {
            throw new SQLServerException(e.getMessage(), e);
        } catch (ExecutionException e) {
            throw getCorrectedException(e, user, authenticationString);
        } finally {
            executorService.shutdown();
        }
    }

    static SqlFedAuthToken getSqlFedAuthTokenPrincipal(SqlFedAuthInfo fedAuthInfo, String aadPrincipalID,
            String aadPrincipalSecret, String authenticationString) throws SQLServerException {
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        try {
            String defaultScopeSuffix = "/.default";
            String scope = fedAuthInfo.spn.endsWith(defaultScopeSuffix) ? fedAuthInfo.spn
                                                                        : fedAuthInfo.spn + defaultScopeSuffix;
            Set<String> scopes = new HashSet<String>();
            scopes.add(scope);
            IClientCredential credential = ClientCredentialFactory.createFromSecret(aadPrincipalSecret);
            ConfidentialClientApplication clientApplication = ConfidentialClientApplication
                    .builder(aadPrincipalID, credential).executorService(executorService).authority(fedAuthInfo.stsurl)
                    .build();
            final CompletableFuture<IAuthenticationResult> future = clientApplication
                    .acquireToken(ClientCredentialParameters.builder(scopes).build());
            final IAuthenticationResult authenticationResult = future.get();
            return new SqlFedAuthToken(authenticationResult.accessToken(), authenticationResult.expiresOnDate());
        } catch (MalformedURLException | InterruptedException e) {
            throw new SQLServerException(e.getMessage(), e);
        } catch (ExecutionException e) {
            throw getCorrectedException(e, aadPrincipalID, authenticationString);
        } finally {
            executorService.shutdown();
        }
    }

    static SqlFedAuthToken getSqlFedAuthTokenIntegrated(SqlFedAuthInfo fedAuthInfo,
            String authenticationString) throws SQLServerException {
        ExecutorService executorService = Executors.newFixedThreadPool(1);

        try {
            /*
             * principal name does not matter, what matters is the realm name it gets the username in
             * principal_name@realm_name format
             */
            KerberosPrincipal kerberosPrincipal = new KerberosPrincipal("username");
            String user = kerberosPrincipal.getName();

            if (logger.isLoggable(Level.FINE)) {
                logger.fine(logger.toString() + " realm name is:" + kerberosPrincipal.getRealm());
            }

            final PublicClientApplication clientApplication = PublicClientApplication
                    .builder(ActiveDirectoryAuthentication.JDBC_FEDAUTH_CLIENT_ID).executorService(executorService)
                    .authority(fedAuthInfo.stsurl).build();
            final CompletableFuture<IAuthenticationResult> future = clientApplication
                    .acquireToken(IntegratedWindowsAuthenticationParameters
                            .builder(Collections.singleton(fedAuthInfo.spn + "/.default"), user).build());

            final IAuthenticationResult authenticationResult = future.get();
            return new SqlFedAuthToken(authenticationResult.accessToken(), authenticationResult.expiresOnDate());
        } catch (InterruptedException | IOException e) {
            throw new SQLServerException(e.getMessage(), e);
        } catch (ExecutionException e) {
            throw getCorrectedException(e, "", authenticationString);
        } finally {
            executorService.shutdown();
        }
    }

    private static SQLServerException getCorrectedException(ExecutionException e, String user,
            String authenticationString) {
        if (logger.isLoggable(Level.SEVERE)) {
            logger.fine(logger.toString() + " MSAL exception:" + e.getMessage());
        }

        MessageFormat form = new MessageFormat(SQLServerException.getErrString("R_MSALExecution"));
        Object[] msgArgs = {user, authenticationString};

        if (null == e.getCause() || null == e.getCause().getMessage()) {
            // The case when Future's outcome has no AuthenticationResult but Exception.
            return new SQLServerException(form.format(msgArgs), null);
        } else {
            /*
             * the cause error message uses \\n\\r which does not give correct format change it to \r\n to provide
             * correct format
             */
            String correctedErrorMessage = e.getCause().getMessage().replaceAll("\\\\r\\\\n", "\r\n");
            RuntimeException correctedAuthenticationException = new RuntimeException(correctedErrorMessage);

            /*
             * SQLServerException is caused by ExecutionException, which is caused by AuthenticationException to match
             * the exception tree before error message correction
             */
            ExecutionException correctedExecutionException = new ExecutionException(correctedAuthenticationException);

            return new SQLServerException(form.format(msgArgs), null, 0, correctedExecutionException);
        }
    }
}
