/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.connection;

import android.os.Build;

import com.xabber.android.data.Application;
import com.xabber.android.data.LogManager;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.SettingsManager;
import com.xabber.android.data.account.AccountItem;
import com.xabber.android.data.account.AccountProtocol;
import com.xabber.android.data.account.OAuthManager;
import com.xabber.android.data.account.OAuthResult;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.packet.StreamError;
import org.jivesoftware.smack.proxy.ProxyInfo;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.iqregister.AccountManager;
import org.xbill.DNS.Record;

import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.X509TrustManager;

import de.duenndns.ssl.MemorizingTrustManager;

/**
 * Provides connection workflow.
 * <p/>
 * Warning: SMACK with its connection is going to be removed!
 *
 * @author alexander.ivanov
 */
public class ConnectionThread implements
        org.jivesoftware.smack.ConnectionListener,
        org.jivesoftware.smack.StanzaListener {

    private static Pattern ADDRESS_AND_PORT = Pattern.compile("^(.*):(\\d+)$");

    /**
     * Filter to process all packets.
     */
    private final AcceptAll ACCEPT_ALL = new AcceptAll();

    private final ConnectionItem connectionItem;

    /**
     * SMACK connection.
     */
    private AbstractXMPPConnection xmppConnection;

    /**
     * Thread holder for this connection.
     */
    private final ExecutorService executorService;

    private final AccountProtocol protocol;

    private final String serverName;

    private final String login;

    /**
     * Refresh token for OAuth or regular password.
     */
    private final String token;

    private final String resource;

    private final boolean saslEnabled;

    private final TLSMode tlsMode;

    private final boolean compression;

    private final ProxyType proxyType;

    private final String proxyHost;

    private final int proxyPort;

    private final String proxyUser;

    private final String proxyPassword;

    private boolean started;

    private boolean registerNewAccount;

    public ConnectionThread(final ConnectionItem connectionItem) {
        LogManager.i(this, "NEW connection thread " + connectionItem.getRealJid());

        this.connectionItem = connectionItem;
        executorService = Executors.newSingleThreadExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(
                                runnable,
                                "Connection thread for "
                                        + (connectionItem instanceof AccountItem ? ((AccountItem) connectionItem)
                                        .getAccount() : connectionItem));
                        thread.setPriority(Thread.MIN_PRIORITY);
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        ConnectionManager.getInstance().onConnection(this);
        ConnectionSettings connectionSettings = connectionItem
                .getConnectionSettings();
        protocol = connectionSettings.getProtocol();
        serverName = connectionSettings.getServerName();
        token = connectionSettings.getPassword();
        resource = connectionSettings.getResource();
        saslEnabled = connectionSettings.isSaslEnabled();
        tlsMode = connectionSettings.getTlsMode();
        compression = connectionSettings.useCompression();
        if (saslEnabled && protocol == AccountProtocol.gtalk)
            login = connectionSettings.getUserName() + "@"
                    + connectionSettings.getServerName();
        else
            login = connectionSettings.getUserName();
        proxyType = connectionSettings.getProxyType();
        proxyHost = connectionSettings.getProxyHost();
        proxyPort = connectionSettings.getProxyPort();
        proxyUser = connectionSettings.getProxyUser();
        proxyPassword = connectionSettings.getProxyPassword();
        started = false;
    }

    public AbstractXMPPConnection getXMPPConnection() {
        return xmppConnection;
    }

    public ConnectionItem getConnectionItem() {
        return connectionItem;
    }

    /**
     * Resolve SRV record.
     *
     * @param fqdn
     * @param defaultHost
     * @param defaultPort
     */
    private void srvResolve(final String fqdn, final String defaultHost, final int defaultPort) {
        final Record[] records = DNSManager.getInstance().fetchRecords(fqdn);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onSRVResolved(fqdn, defaultHost, defaultPort, records);
            }
        });
    }

    /**
     * Called when srv records are resolved.
     *
     * @param fqdn
     * @param defaultHost
     * @param defaultPort
     * @param records
     */
    private void onSRVResolved(final String fqdn, final String defaultHost,
                               final int defaultPort, Record[] records) {
        DNSManager.getInstance().onRecordsReceived(fqdn, records);
        final Target target = DNSManager.getInstance().getCurrentTarget(fqdn);
        if (target == null) {
            LogManager.i(this, "Use defaults");
            runOnConnectionThread(new Runnable() {
                @Override
                public void run() {
                    if(proxyType == ProxyType.socks5) {
                      onReady(defaultHost, defaultPort);
                    }
                    else {
                      addressResolve(null, defaultHost, defaultPort, true);
                    }
                }
            });
        } else {
            runOnConnectionThread(new Runnable() {
                @Override
                public void run() {
                    if(proxyType == ProxyType.socks5) {
                      onReady(target.getHost(), target.getPort());
                    }
                    else {
                      addressResolve(fqdn, target.getHost(), target.getPort(),
                              true);
                    }
                }
            });
        }
    }

    /**
     * Resolves address to connect to.
     *
     * @param fqdn         server name of subsequence SRV lookup. Should be
     *                     <code>null</code> for custom host work flow.
     * @param host
     * @param port
     * @param firstRequest
     */
    private void addressResolve(final String fqdn, final String host, final int port, final boolean firstRequest) {
        LogManager.i(this, "Resolve " + host + ":" + port);
        final InetAddress[] addresses = DNSManager.getInstance().fetchAddresses(host);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onAddressResolved(fqdn, host, port, firstRequest, addresses);
            }
        });
    }

    /**
     * Called when address resolved are resolved.
     *
     * @param fqdn
     * @param host
     * @param port
     * @param firstRequest
     * @param addresses
     */
    private void onAddressResolved(final String fqdn, final String host,
                                   final int port, boolean firstRequest, final InetAddress[] addresses) {
        DNSManager.getInstance().onAddressesReceived(host, addresses);
        InetAddress address = DNSManager.getInstance().getNextAddress(host);
        if (address != null) {
            onReady(address, port);
            return;
        }
        if (fqdn == null) {
            if (firstRequest) {
                onAddressResolved(null, host, port, false, addresses);
                return;
            }
        } else {
            DNSManager.getInstance().getNextTarget(fqdn);
            if (DNSManager.getInstance().getCurrentTarget(fqdn) == null
                    && firstRequest)
                DNSManager.getInstance().getNextTarget(fqdn);
            final Target target = DNSManager.getInstance().getCurrentTarget(
                    fqdn);
            if (target != null) {
                runOnConnectionThread(new Runnable() {
                    @Override
                    public void run() {
                        addressResolve(fqdn, target.getHost(),
                                target.getPort(), false);
                    }
                });
                return;
            }
        }
        // TODO correct type of exception
        RuntimeException exception = new RuntimeException("There is no available address.");
        LogManager.exception(this, exception);
        connectionClosedOnError(exception);
    }

    /**
     * Called when configuration is ready and xmpp connection instance can be
     * created.
     *
     */
    private void onReady(final InetAddress address, final int port) {
        LogManager.i(this, "Use " + address);
        ProxyInfo proxy = proxyType.getProxyInfo(proxyHost, proxyPort,
                proxyUser, proxyPassword);

        XMPPTCPConnectionConfiguration.Builder builder = XMPPTCPConnectionConfiguration.builder();
        builder.setHost(address.getHostAddress());
        builder.setPort(port);
        builder.setServiceName(serverName);


//        ConnectionConfiguration connectionConfiguration = new ConnectionConfiguration(
//                address.getHostAddress(), port, serverName, proxy);
        onReady(builder);
    }

    private void onReady(final String host, final int port) {
        LogManager.i(this, "Use remote DNS for " + host);
        ProxyInfo proxy = proxyType.getProxyInfo(proxyHost, proxyPort, proxyUser, proxyPassword);
//        ConnectionConfiguration connectionConfiguration = new ConnectionConfiguration(
//                host, port, serverName, proxy);

        XMPPTCPConnectionConfiguration.Builder builder = XMPPTCPConnectionConfiguration.builder();
        builder.setHost(host);
        builder.setPort(port);
        builder.setServiceName(serverName);

        onReady(builder);
    }

    private void onReady(XMPPTCPConnectionConfiguration.Builder builder) {
        builder.setKeystoreType("AndroidCAStore");

        // Disable smack`s reconnection.
//        connectionConfiguration.setReconnectionAllowed(false);
        // We will send custom presence.
        builder.setSendPresence(false);


        if (SettingsManager.securityCheckCertificate()) {
//            connectionConfiguration.setExpiredCertificatesCheckEnabled(true);
//            connectionConfiguration.setNotMatchingDomainCheckEnabled(true);
//            connectionConfiguration.setSelfSignedCertificateEnabled(false);
//            connectionConfiguration.setVerifyChainEnabled(true);
//            connectionConfiguration.setVerifyRootCAEnabled(true);
//            connectionConfiguration.setCertificateListener(CertificateManager
//                    .getInstance().createCertificateListener(connectionItem));
        } else {
//            connectionConfiguration.setExpiredCertificatesCheckEnabled(false);
//            connectionConfiguration.setNotMatchingDomainCheckEnabled(false);
//            connectionConfiguration.setSelfSignedCertificateEnabled(true);
//            connectionConfiguration.setVerifyChainEnabled(false);
//            connectionConfiguration.setVerifyRootCAEnabled(false);
        }

//        connectionConfiguration.setSASLAuthenticationEnabled(saslEnabled);
        builder.setSecurityMode(tlsMode.getSecurityMode());
        builder.setCompressionEnabled(compression);

        {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                MemorizingTrustManager mtm = new MemorizingTrustManager(Application.getInstance());
                sslContext.init(null, new X509TrustManager[]{mtm}, new java.security.SecureRandom());
                builder.setCustomSSLContext(sslContext);
                builder.setHostnameVerifier(
                        mtm.wrapHostnameVerifier(new org.apache.http.conn.ssl.StrictHostnameVerifier()));
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                e.printStackTrace();
            }
        }

        xmppConnection = new XMPPTCPConnection(builder.build());
        xmppConnection.addAsyncStanzaListener(this, ACCEPT_ALL);
        xmppConnection.addConnectionListener(this);

        // We use own roster management.
        Roster.getInstanceFor(xmppConnection).setRosterLoadedAtLogin(false);

        connectionItem.onSRVResolved(this);
        final String password = OAuthManager.getInstance().getPassword(protocol, token);
        if (password != null) {
            runOnConnectionThread(new Runnable() {
                @Override
                public void run() {
                    connect(password);
                }
            });
        } else {
            runOnConnectionThread(new Runnable() {
                @Override
                public void run() {
                    passwordRequest();
                }
            });
        }
    }

    /**
     * Request to renew password using OAuth.
     */
    private void passwordRequest() {
        final OAuthResult oAuthResult;
        try {
            oAuthResult = OAuthManager.getInstance().requestAccessToken(
                    protocol, token);
        } catch (NetworkException e) {
            throw new RuntimeException(e);
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onPasswordReceived(oAuthResult);
            }
        });
    }

    /**
     * Called when password has been renewed.
     *
     * @param oAuthResult
     */
    private void onPasswordReceived(final OAuthResult oAuthResult) {
        OAuthManager.getInstance().onAccessTokenReceived(oAuthResult);
        if (oAuthResult == null) {
            connectionItem.onAuthFailed();
            return;
        }
        connectionItem.onPasswordChanged(oAuthResult.getRefreshToken());
        final String password = oAuthResult.getAccessToken();
        runOnConnectionThread(new Runnable() {
            @Override
            public void run() {
                connect(password);
            }
        });
    }

    /**
     * Server requests us to see another host.
     *
     * @param target
     */
    private void seeOtherHost(String target) {
        Matcher matcher = ADDRESS_AND_PORT.matcher(target);
        int defaultPort = 5222;
        if (matcher.matches()) {
            String value = matcher.group(2);
            try {
                defaultPort = Integer.valueOf(value);
                target = matcher.group(1);
            } catch (NumberFormatException e2) {
            }
        }
        final String fqdn = target;
        final int port = defaultPort;
        // TODO: Check for the same address.
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectionItem.onSeeOtherHost(ConnectionThread.this, fqdn,
                        port, true);
            }
        });
    }

    /**
     * Connect to the server.
     *
     * @param password
     */
    private void connect(final String password) {
        try {
            xmppConnection.connect();
        } catch (SmackException | IOException | XMPPException e) {
            e.printStackTrace();
            checkForCertificateError(e);
            if (!checkForSeeOtherHost(e)) {
                // There is no connection listeners yet, so we call onClose.
                throw new RuntimeException(e);
            }
        }

//        try {
//            xmppConnection.connect();
//        } catch (XMPPException e) {
//            checkForCertificateError(e);
//            if (!checkForSeeOtherHost(e)) {
//                // There is no connection listeners yet, so we call onClose.
//                throw new RuntimeException(e);
//            }
//        } catch (IllegalStateException e) {
//            // There is no connection listeners yet, so we call onClose.
//            // connectionCreated() in other modules can fail.
//            throw new RuntimeException(e);
//        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onConnected(password);
            }
        });
    }

    /**
     * Check for certificate exception.
     *
     * @param e
     */
    private void checkForCertificateError(Exception e) {
        if (!(e instanceof SSLException)) {
            return;
        }
        Throwable e2 = e.getCause();
        if (!(e2 instanceof CertificateException)) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectionItem.onInvalidCertificate();
            }
        });
    }

    /**
     * Check for see other host exception.
     *
     * @param e
     * @return <code>true</code> if {@link StreamError.Condition#see_other_host} has
     * been found.
     */
    private boolean checkForSeeOtherHost(Exception e) {
        if (e instanceof XMPPException.StreamErrorException) {
            XMPPException.StreamErrorException streamErrorException = (XMPPException.StreamErrorException) e;
            if (streamErrorException.getStreamError().getCondition() == StreamError.Condition.see_other_host) {
                String target = streamErrorException.getStreamError().getConditionText();
                if (target == null || "".equals(target))
                    return false;
                LogManager.i(this, "See other host: " + target);
                seeOtherHost(target);
                return true;
            }
        }
        return false;
    }

    /**
     * Connection to the server has been established.
     *
     * @param password
     */
    private void onConnected(final String password) {
        connectionItem.onConnected(this);
        ConnectionManager.getInstance().onConnected(this);
        if(registerNewAccount) {
            runOnConnectionThread(new Runnable() {
                @Override
                public void run() {
                    registerAccount(password);
                }
            });
        }
        else {
            runOnConnectionThread(new Runnable() {
                @Override
                public void run() {
                    authorization(password);
                }
            });
        }
    }

    /**
     * Register new account.
     * 
     * @param password
     */
    private void registerAccount(final String password) {
        try {
            AccountManager.getInstance(xmppConnection).createAccount(login, password);
        } catch (SmackException.NoResponseException | SmackException.NotConnectedException | XMPPException.XMPPErrorException e) {
            LogManager.exception(connectionItem, e);
            connectionClosedOnError(e);
            // Server will destroy connection, but we can speedup
            // it.
            xmppConnection.disconnect();
            return;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onAccountRegistered(password);
            }
        });
    }

    /**
     * New account has been registerd on the server.
     * 
     * @param password
     */
    private void onAccountRegistered(final String password) {
        LogManager.i(this, "Account registered");
        connectionItem.onAccountRegistered(this);
        runOnConnectionThread(new Runnable() {
            @Override
            public void run() {
                authorization(password);
            }
        });
    }

    /**
     * Login.
     *
     * @param password
     */
    private void authorization(String password) {
        try {
            xmppConnection.login(login, password, resource);
        } catch (IOException | SmackException | XMPPException e) {
            e.printStackTrace();
            connectionClosedOnError(e);
            xmppConnection.disconnect();
            return;
        }
//        } catch (XMPPException e) {
//            LogManager.exception(connectionItem, e);
//            // SASLAuthentication#authenticate(String,String,String)
//            boolean SASLfailed = e.getMessage() != null
//                    && e.getMessage().startsWith("SASL authentication ")
//                    && !e.getMessage().endsWith("temporary-auth-failure");
//            // NonSASLAuthentication#authenticate(String,String,String)
//            // Authentication request failed (doesn't supported),
//            // error was returned after negotiation or
//            // authentication failed.
//            boolean NonSASLfailed = e.getXMPPError() != null
//                    && "Authentication failed.".equals(e.getMessage());
//            if (SASLfailed || NonSASLfailed) {
//                // connectionClosed can be called before from reader thread,
//                // so don't check whether connection is managed.
//                Application.getInstance().runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        // Login failed. We don`t want to reconnect.
//                        connectionItem.onAuthFailed();
//                    }
//                });
//                connectionClosed();
//            } else
//                connectionClosedOnError(e);
//            // Server will destroy connection, but we can speedup
//            // it.
//            xmppConnection.disconnect();
//            return;
//        } catch (SmackException | IOException e) {
//            e.printStackTrace();
//            return;
//        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                onAuthorized();
            }
        });
    }

    /**
     * Authorization passed.
     */
    private void onAuthorized() {
        connectionItem.onAuthorized(this);
        ConnectionManager.getInstance().onAuthorized(this);
        if (connectionItem instanceof AccountItem) {
            com.xabber.android.data.account.AccountManager.getInstance().removeAuthorizationError(
                    ((AccountItem) connectionItem).getAccount());
        }
        shutdown();
    }

    @Override
    public void connected(XMPPConnection connection) {
    }

    @Override
    public void authenticated(XMPPConnection connection, boolean resumed) {
    }

    @Override
    public void connectionClosed() {
        // Can be called on error, e.g. XMPPConnection#initConnection().
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectionItem.onClose(ConnectionThread.this);
            }
        });
    }

    @Override
    public void connectionClosedOnError(Exception e) {
        checkForCertificateError(e);
        if (checkForSeeOtherHost(e))
            return;
        connectionClosed();
    }

    @Override
    public void reconnectingIn(int seconds) {
    }

    @Override
    public void reconnectionSuccessful() {
    }

    @Override
    public void reconnectionFailed(Exception e) {
    }

    @Override
    public void processPacket(final Stanza packet) throws SmackException.NotConnectedException {
        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ConnectionManager.getInstance().processPacket(ConnectionThread.this, packet);
            }
        });
    }

    /**
     * Filter to accept all packets.
     *
     * @author alexander.ivanov
     */
    static class AcceptAll implements StanzaFilter {
        @Override
        public boolean accept(Stanza packet) {
            return true;
        }
    }

    /**
     * Start connection.
     * <p/>
     * This function can be called only once.
     *
     * @param fqdn         Fully Qualified Domain Names.
     * @param port         Preferred port.
     * @param useSRVLookup Whether SRV lookup should be used.
     */
    synchronized void start(final String fqdn, final int port,
                            final boolean useSRVLookup,
                            final boolean registerNewAccount) {
        LogManager.i(this, "start: " + fqdn);

        if (started)
            throw new IllegalStateException();
        started = true;
        this.registerNewAccount = registerNewAccount;
        runOnConnectionThread(new Runnable() {
            @Override
            public void run() {
                if (useSRVLookup)
                    srvResolve(fqdn, fqdn, port);
                else {
                    if(proxyType == ProxyType.socks5) {
                      onReady(fqdn, port);
                    }
                    else {
                      addressResolve(null, fqdn, port, true);
                    }
                }
            }
        });
    }

    /**
     * Stop connection.
     * <p/>
     * start MUST BE CALLED FIRST.
     */
    void shutdown() {
        executorService.shutdownNow();
    }

    /**
     * Submit task to be executed in connection thread.
     *
     * @param runnable
     */
    private void runOnConnectionThread(final Runnable runnable) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (!connectionItem.isManaged(ConnectionThread.this))
                    return;
                try {
                    runnable.run();
                } catch (RuntimeException e) {
                    LogManager.exception(connectionItem, e);
                    connectionClosedOnError(e);
                }
            }
        });
    }

    /**
     * Commit changes received from connection thread in UI thread.
     *
     * @param runnable
     */
    private void runOnUiThread(final Runnable runnable) {
        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!connectionItem.isManaged(ConnectionThread.this))
                    return;
                runnable.run();
            }
        });
    }

}
