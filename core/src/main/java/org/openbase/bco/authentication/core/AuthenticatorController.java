package org.openbase.bco.authentication.core;

/*-
 * #%L
 * BCO Authentication Core
 * %%
 * Copyright (C) 2017 openbase.org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import org.openbase.bco.authentication.core.mock.MockAuthenticationRegistry;
import org.openbase.bco.authentication.lib.AuthenticationServerHandler;
import org.openbase.bco.authentication.lib.EncryptionHelper;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.InitializationException;
import org.openbase.jul.iface.Launchable;
import org.openbase.jul.iface.VoidInitializable;
import org.openbase.bco.authentication.lib.jp.JPAuthenticationScope;
import org.openbase.jps.core.JPService;
import org.openbase.jps.exception.JPNotAvailableException;
import org.openbase.jul.extension.rsb.com.NotInitializedRSBLocalServer;
import org.openbase.jul.extension.rsb.com.RPCHelper;
import org.openbase.jul.extension.rsb.com.RSBFactoryImpl;
import org.openbase.jul.extension.rsb.com.RSBSharedConnectionConfig;
import org.openbase.jul.extension.rsb.iface.RSBLocalServer;
import org.openbase.jul.schedule.GlobalCachedExecutorService;
import org.openbase.jul.schedule.WatchDog;
import rst.domotic.authentication.TicketAuthenticatorWrapperType.TicketAuthenticatorWrapper;
import rst.domotic.authentication.TicketSessionKeyWrapperType.TicketSessionKeyWrapper;
import org.openbase.bco.authentication.lib.AuthenticationService;
import org.openbase.bco.authentication.lib.jp.JPAuthenticationSimulationMode;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.exception.RejectedException;
import org.openbase.jul.exception.printer.ExceptionPrinter;
import org.openbase.jul.exception.printer.LogLevel;
import org.slf4j.LoggerFactory;
import rst.domotic.authentication.LoginCredentialsType.LoginCredentials;

/**
 *
 * @author <a href="mailto:thuxohl@techfak.uni-bielefeld.de">Tamino Huxohl</a>
 */
public class AuthenticatorController implements AuthenticationService, Launchable<Void>, VoidInitializable {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(AuthenticatorController.class);

    private RSBLocalServer server;
    private WatchDog serverWatchDog;

    private final byte[] ticketGrantingServicePrivateKey;
    private final byte[] serviceServerPrivateKey;
    private final Map<String, byte[]> ticketGrantingServiceSessionKeyMap;
    private final Map<String, byte[]> serviceServerSessionKeyMap;

    private final AuthenticationRegistry authenticationRegistry;

    public AuthenticatorController() {
        this(new AuthenticationRegistry());
    }

    public AuthenticatorController(AuthenticationRegistry authenticationRegistry) {
        this.server = new NotInitializedRSBLocalServer();

        this.ticketGrantingServicePrivateKey = EncryptionHelper.generateKey();
        this.serviceServerPrivateKey = EncryptionHelper.generateKey();
        this.ticketGrantingServiceSessionKeyMap = new HashMap<>();
        this.serviceServerSessionKeyMap = new HashMap<>();

        boolean simulation = false;
        try {
            simulation = JPService.getProperty(JPAuthenticationSimulationMode.class).getValue();
        } catch (JPNotAvailableException ex) {
            LOGGER.warn("Could not check simulation property. Starting in normal mode.", ex);
        }
        if (simulation) {
            this.authenticationRegistry = new MockAuthenticationRegistry();
        } else {
            this.authenticationRegistry = authenticationRegistry;
        }
    }

    @Override
    public void init() throws InitializationException, InterruptedException {
        try {
            server = RSBFactoryImpl.getInstance().createSynchronizedLocalServer(JPService.getProperty(JPAuthenticationScope.class).getValue(), RSBSharedConnectionConfig.getParticipantConfig());

            // register rpc methods.
            RPCHelper.registerInterface(AuthenticationService.class, this, server);

            serverWatchDog = new WatchDog(server, "AuthenticatorWatchDog");
        } catch (JPNotAvailableException | CouldNotPerformException ex) {
            throw new InitializationException(this, ex);
        }

        authenticationRegistry.init();
    }

    @Override
    public void activate() throws CouldNotPerformException, InterruptedException {
        serverWatchDog.activate();
    }

    @Override
    public void deactivate() throws CouldNotPerformException, InterruptedException {
        if (serverWatchDog != null) {
            serverWatchDog.deactivate();
        }
    }

    @Override
    public boolean isActive() {
        if (serverWatchDog != null) {
            return serverWatchDog.isActive();
        } else {
            return false;
        }
    }

    @Override
    public Future<TicketSessionKeyWrapper> requestTicketGrantingTicket(String clientId) throws CouldNotPerformException {
        return GlobalCachedExecutorService.submit(() -> {
            try {
                String[] split = clientId.split("@", 2);
                String userName = split[0];
                byte[] passwordHash = authenticationRegistry.getCredentials(userName);
                ticketGrantingServiceSessionKeyMap.put(userName, EncryptionHelper.generateKey());
                return AuthenticationServerHandler.handleKDCRequest(clientId, passwordHash, "", ticketGrantingServiceSessionKeyMap.get(userName), ticketGrantingServicePrivateKey);
            } catch (NotAvailableException ex) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable(ex, LOGGER, LogLevel.ERROR);
            } catch (InterruptedException | CouldNotPerformException | IOException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Internal server error. Please try again.");
            }
        });
    }

    @Override
    public Future<TicketSessionKeyWrapper> requestClientServerTicket(TicketAuthenticatorWrapper ticketAuthenticatorWrapper) throws CouldNotPerformException {
        return GlobalCachedExecutorService.submit(() -> {
            try {
                return AuthenticationServerHandler.handleTGSRequest(ticketGrantingServiceSessionKeyMap, serviceServerSessionKeyMap, ticketGrantingServicePrivateKey, serviceServerPrivateKey, ticketAuthenticatorWrapper);
            } catch (RejectedException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw new RejectedException(ex.getMessage());
            } catch (StreamCorruptedException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw new StreamCorruptedException(ex.getMessage());
            } catch (IOException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Internal server error. Please try again.");
            }
        });
    }

    @Override
    public Future<TicketAuthenticatorWrapper> validateClientServerTicket(TicketAuthenticatorWrapper ticketAuthenticatorWrapper) throws CouldNotPerformException {
        return GlobalCachedExecutorService.submit(() -> {
            try {
                return AuthenticationServerHandler.handleSSRequest(serviceServerSessionKeyMap, serviceServerPrivateKey, ticketAuthenticatorWrapper);
            } catch (RejectedException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw new RejectedException(ex.getMessage());
            } catch (StreamCorruptedException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw new StreamCorruptedException(ex.getMessage());
            } catch (IOException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Internal server error. Please try again.");
            }
        });
    }

    @Override
    public Future<Void> registerClient(LoginCredentials loginCredentials) throws CouldNotPerformException {
        return GlobalCachedExecutorService.submit(() -> {
            authenticationRegistry.setCredentials(loginCredentials.getId(), loginCredentials.getNewCredentials().toByteArray());
            return null;
        });

    }
}
