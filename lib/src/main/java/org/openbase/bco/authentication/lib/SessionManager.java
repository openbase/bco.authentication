package org.openbase.bco.authentication.lib;

/*-
 * #%L
 * BCO Authentication Library
 * %%
 * Copyright (C) 2017 openbase.org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.openbase.bco.authentication.core.mock.MockClientStore;
import org.openbase.bco.authentication.lib.jp.JPAuthenticationSimulationMode;
import org.openbase.jps.core.JPService;
import org.openbase.jps.exception.JPNotAvailableException;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.FatalImplementationErrorException;
import org.openbase.jul.exception.InitializationException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.exception.PermissionDeniedException;
import org.openbase.jul.exception.RejectedException;
import org.openbase.jul.exception.printer.ExceptionPrinter;
import org.openbase.jul.exception.printer.LogLevel;
import org.slf4j.LoggerFactory;
import rst.domotic.authentication.LoginCredentialsChangeType;
import rst.domotic.authentication.LoginCredentialsChangeType.LoginCredentialsChange;
import rst.domotic.authentication.TicketAuthenticatorWrapperType.TicketAuthenticatorWrapper;
import rst.domotic.authentication.TicketSessionKeyWrapperType;

/**
 *
 * @author <a href="mailto:sfast@techfak.uni-bielefeld.de">Sebastian Fast</a>
 */
public class SessionManager {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(SessionManager.class);

    private TicketAuthenticatorWrapper ticketAuthenticatorWrapper;
    private byte[] sessionKey;
    
    private final Store store;
    
    // TODO: Set this during login
    // TODO: maybe replace this with something else
    private boolean keepUserLoggedIn;
    
    // remember id of client during session
    private String clientId;

    public SessionManager() {
        this(new ClientStore());
    }

    public SessionManager(Store userStore) {
        // TODO: maybe replace this with something else
        this.keepUserLoggedIn = true;
        
        // load registry
        boolean simulation = false;
        try {
            simulation = JPService.getProperty(JPAuthenticationSimulationMode.class).getValue();
        } catch (JPNotAvailableException ex) {
            LOGGER.warn("Could not check simulation property. Starting in normal mode.", ex);
        }
        if (simulation) {
            this.store = new MockClientStore();
        } else {
            this.store = userStore;
        }
    }
    
    public void init() throws InitializationException {
        this.store.init();
    }

    public TicketAuthenticatorWrapper getTicketAuthenticatorWrapper() {
        return ticketAuthenticatorWrapper;
    }

    public void setTicketAuthenticatorWrapper(TicketAuthenticatorWrapper ticketAuthenticatorWrapper) {
        this.ticketAuthenticatorWrapper = ticketAuthenticatorWrapper;
    }

    public byte[] getSessionKey() {
        return sessionKey;
    }

    public void initializeServiceServerRequest() throws RejectedException {
        try {
            this.ticketAuthenticatorWrapper = AuthenticationClientHandler.initServiceServerRequest(this.getSessionKey(), this.getTicketAuthenticatorWrapper());
        } catch (IOException ex) {
            throw new RejectedException("Initializing request rejected", ex);
        }
    }

    /** TODO: Save Login data, if keepUserLoggedIn set to true
     * Perform a login for a given userId and password.
     *
     * @param userId Identifier of the user
     * @param userPassword Password of the user
     * @return Returns Returns an TicketAuthenticatorWrapperWrapper containing
     * both the ClientServerTicket and Authenticator
     * @throws StreamCorruptedException If the password was wrong.
     * @throws NotAvailableException If the entered clientId could not be found.
     * @throws CouldNotPerformException In case of a communication error between client and server.
     */
    public boolean login(String userId, String userPassword) throws StreamCorruptedException, CouldNotPerformException, NotAvailableException {
        byte[] clientPasswordHash = EncryptionHelper.hash(userPassword);
        return this.internalLogin(userId, clientPasswordHash, true);
    }

    /**
     * Perform a login for a given clientId.
     *
     * @param clientId Identifier of the user
     * @return Returns Returns an TicketAuthenticatorWrapperWrapper containing
     * both the ClientServerTicket and Authenticator
     * @throws StreamCorruptedException If the password was wrong.
     * @throws NotAvailableException If the entered clientId could not be found.
     * @throws CouldNotPerformException In case of a communication error between client and server.
     */
    public boolean login(String clientId) throws StreamCorruptedException, CouldNotPerformException, NotAvailableException {
        // TODO: replace both of this with some kind of retrievel from a store
        this.clientId = clientId;
        byte[] key = this.store.getCredentials(clientId);
        return this.internalLogin(clientId, key, false);
    }

    /**
     * Perform a login for a given userId and password.
     *
     * @param id Identifier of the user or client
     * @param key Password or private key of the user or client
     * @return Returns Returns an TicketAuthenticatorWrapperWrapper containing
     * both the ClientServerTicket and Authenticator
     * @throws StreamCorruptedException If the password was wrong.
     * @throws NotAvailableException If the entered clientId could not be found.
     * @throws CouldNotPerformException In case of a communication error between client and server.
     */
    private boolean internalLogin(String id, byte[] key, boolean isUser) throws StreamCorruptedException, CouldNotPerformException, NotAvailableException {
        if (this.isLoggedIn()) throw new CouldNotPerformException("You are already logged in.");
        
        try {
            // prepend clientId to userId for TicketGranteningTicket request
            String userIdAtClientId = "@" + this.clientId;
            if (isUser == true) userIdAtClientId = id + userIdAtClientId;
            
            // request TGT
            TicketSessionKeyWrapperType.TicketSessionKeyWrapper ticketSessionKeyWrapper = CachedAuthenticationRemote.getRemote().requestTicketGrantingTicket(userIdAtClientId).get();

            // handle KDC response on client side
            List<Object> list = AuthenticationClientHandler.handleKeyDistributionCenterResponse(id, key, isUser, ticketSessionKeyWrapper);
            TicketAuthenticatorWrapper taw = (TicketAuthenticatorWrapper) list.get(0); // save at somewhere temporarily
            byte[] ticketGrantingServiceSessionKey = (byte[]) list.get(1); // save TGS session key somewhere on client side

            // request CST
            ticketSessionKeyWrapper = CachedAuthenticationRemote.getRemote().requestClientServerTicket(taw).get();

            // handle TGS response on client side
            list = AuthenticationClientHandler.handleTicketGrantingServiceResponse(id, ticketGrantingServiceSessionKey, ticketSessionKeyWrapper);
            this.ticketAuthenticatorWrapper = (TicketAuthenticatorWrapper) list.get(0); // save at somewhere temporarily
            this.sessionKey = (byte[]) list.get(1); // save SS session key somewhere on client side
            
            // save credentials
            // TODO: isAdmin flat is irrelevant here - client side doesn't need to know about that 
            if (!this.store.hasEntry(id))
                this.store.addCredentials(id, key, false);
            
            return true;
        } catch (StreamCorruptedException ex) {
            throw new CouldNotPerformException("The password you have entered was wrong. Please try again!");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();

            if (cause instanceof NotAvailableException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((NotAvailableException) cause, LOGGER, LogLevel.ERROR);
            }

            if (cause instanceof StreamCorruptedException) {
                ExceptionPrinter.printHistory(cause, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Decryption failed. Please login again.", cause);
            }

            // RejectedException is thrown if the timestamp in the Authenticator does not fit to time period in TGT
            // or, if the clientID in Authenticator does not match the clientID in the TGT.
            // This should never occur, as Authenticator and Ticket are generated by the client handler for the same user.
            if (cause instanceof RejectedException) {
                throw new FatalImplementationErrorException(this, cause);
            }

            ExceptionPrinter.printHistory(cause, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Internal server error.", cause);
        } catch (CouldNotPerformException | IOException | InterruptedException ex) {
            throw new CouldNotPerformException("Login failed! Please try again.", ex);
        }
    }

    /**
     * Logs a user out by setting CST and session key to null
     */
    public void logout() {
        this.ticketAuthenticatorWrapper = null;
        this.sessionKey = null;
    }

    /**
     * determines if a user is logged in.
     * does not validate ClientServerTicket and SessionKey
     *
     * @return Returns true if logged in otherwise false
     */
    public boolean isLoggedIn() {
        return this.ticketAuthenticatorWrapper != null && this.sessionKey != null;
    }
    
    /**
     * determines if a user is authenticated.
     * does validate ClientServerTicket and SessionKey
     *
     * @return Returns true if authenticated otherwise appropriate exception
     * @throws org.openbase.jul.exception.CouldNotPerformException In case of a communication error between client and server.
     */
    public boolean isAuthenticated() throws CouldNotPerformException {
        if (!this.isLoggedIn()) throw new CouldNotPerformException("Please log in first!");
        
        try {
            this.ticketAuthenticatorWrapper = AuthenticationClientHandler.initServiceServerRequest(this.sessionKey, this.ticketAuthenticatorWrapper);
            TicketAuthenticatorWrapper wrapper = CachedAuthenticationRemote.getRemote().validateClientServerTicket(this.ticketAuthenticatorWrapper).get();
            wrapper = AuthenticationClientHandler.handleServiceServerResponse(
                    this.sessionKey,
                    this.ticketAuthenticatorWrapper,
                    wrapper);
            this.ticketAuthenticatorWrapper = wrapper;
            return true;
        } catch (IOException ex) {
            this.logout();
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Decryption failed. You have been logged out for security reasons. Please log in again.");
        } catch (InterruptedException ex) {
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Action was interrupted.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();

            if (cause instanceof RejectedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((RejectedException) cause, LOGGER, LogLevel.ERROR);
            }

            if (cause instanceof PermissionDeniedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((PermissionDeniedException) cause, LOGGER, LogLevel.ERROR);
            }

            ExceptionPrinter.printHistory(cause, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Internal server error.", cause);
        }
    }

    /**
     * Changes the login credentials for a given user.
     *
     * @param clientId ID of the user / client whose credentials should be changed.
     * @param oldCredentials Old credentials, needed for verification.
     * @param newCredentials New credentials to be set.
     * @throws CouldNotPerformException In case of a communication error between client and server.
     */
    public void changeCredentials(String clientId, String oldCredentials, String newCredentials) throws CouldNotPerformException {
        if (!this.isLoggedIn()) {
            throw new CouldNotPerformException("Please log in first!");
        }

        try {
            ticketAuthenticatorWrapper = AuthenticationClientHandler.initServiceServerRequest(sessionKey, ticketAuthenticatorWrapper);
            byte[] oldHash = EncryptionHelper.hash(oldCredentials);
            byte[] newHash = EncryptionHelper.hash(newCredentials);

            LoginCredentialsChangeType.LoginCredentialsChange loginCredentialsChange = LoginCredentialsChangeType.LoginCredentialsChange.newBuilder()
                    .setId(clientId)
                    .setOldCredentials(EncryptionHelper.encrypt(oldHash, sessionKey))
                    .setNewCredentials(EncryptionHelper.encrypt(newHash, sessionKey))
                    .setTicketAuthenticatorWrapper(ticketAuthenticatorWrapper)
                    .build();

            TicketAuthenticatorWrapper newTicketAuthenticatorWrapper = CachedAuthenticationRemote.getRemote().changeCredentials(loginCredentialsChange).get();
            AuthenticationClientHandler.handleServiceServerResponse(sessionKey, ticketAuthenticatorWrapper, newTicketAuthenticatorWrapper);
            
            // save new credentials
            if (this.store.hasEntry(clientId))
                this.store.setCredentials(clientId, newHash);
            
        } catch (IOException ex) {
            this.logout();
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Decryption failed. You have been logged out for security reasons. Please log in again.");
        } catch (RejectedException | NotAvailableException ex) {
            throw ExceptionPrinter.printHistoryAndReturnThrowable(ex, LOGGER, LogLevel.ERROR);
        } catch (InterruptedException ex) {
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Action was interrupted.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();

            if (cause instanceof RejectedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((RejectedException) cause, LOGGER, LogLevel.ERROR);
            }

            if (cause instanceof PermissionDeniedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((PermissionDeniedException) cause, LOGGER, LogLevel.ERROR);
            }

            ExceptionPrinter.printHistory(cause, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Internal server error.", cause);
        }
    }
    
    /**
     * Registers a client.
     * 
     * @param clientId the id of the client
     * @throws org.openbase.jul.exception.CouldNotPerformException
     */
    public void registerClient(String clientId) throws CouldNotPerformException {        
        KeyPair keyPair = EncryptionHelper.generateKeyPair();
        boolean isAdmin = false;
        this.internalRegister(clientId, keyPair.getPublic().getEncoded(), isAdmin);
        if (this.store.hasEntry(clientId))
            this.store.removeEntry(clientId);
        this.store.addCredentials(clientId, keyPair.getPrivate().getEncoded(), isAdmin);
    }
    
    /**
     * Registers a user.
     * 
     * @param userId the id of the user
     * @param password the password of the user
     * @param isAdmin flag if user should be an administrator
     * @throws org.openbase.jul.exception.CouldNotPerformException
     */
    public void registerUser(String userId, String password, boolean isAdmin) throws CouldNotPerformException {        
        byte[] key = EncryptionHelper.hash(password);
        this.internalRegister(userId, key, isAdmin);
    }
    
    /**
     * Registers a user or client.
     * Assumes an administrator who has permissions for this already exists and is logged in with current session manager.
     * Overwrites duplicate entries on client, if entry to be registered does not exist on server.
     * Does not overwrite duplicate entries on client, if entry does exist on server.
     * 
     * @param userId the id of the user
     * @param password the password of the user
     * @param isAdmin flag if user should be an administrator
     * @throws org.openbase.jul.exception.CouldNotPerformException
     */
    private void internalRegister(String id, byte[] key, boolean isAdmin) throws CouldNotPerformException {
        if (!this.isLoggedIn()) throw new CouldNotPerformException("Please log in first!");
        
        try {
            ticketAuthenticatorWrapper = AuthenticationClientHandler.initServiceServerRequest(this.sessionKey, this.ticketAuthenticatorWrapper);

            LoginCredentialsChange loginCredentialsChange = LoginCredentialsChange.newBuilder()
                    .setId(id)
                    .setNewCredentials(EncryptionHelper.encrypt(key, this.sessionKey))
                    .setTicketAuthenticatorWrapper(this.ticketAuthenticatorWrapper)
                    .setAdmin(isAdmin)
                    .build();
         
            TicketAuthenticatorWrapper wrapper = CachedAuthenticationRemote.getRemote().register(loginCredentialsChange).get();
            AuthenticationClientHandler.handleServiceServerResponse(this.sessionKey, this.ticketAuthenticatorWrapper, wrapper);        
        } catch (IOException ex) {
            this.logout();
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Decryption failed. You have been logged out for security reasons. Please log in again.");
        } catch (RejectedException | NotAvailableException ex) {
            throw ExceptionPrinter.printHistoryAndReturnThrowable(ex, LOGGER, LogLevel.ERROR);
        } catch (InterruptedException ex) {
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Action was interrupted.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();

            if (cause instanceof RejectedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((RejectedException) cause, LOGGER, LogLevel.ERROR);
            }

            if (cause instanceof PermissionDeniedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((PermissionDeniedException) cause, LOGGER, LogLevel.ERROR);
            }

            ExceptionPrinter.printHistory(cause, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Internal server error.", cause);
        }
    }
    
    public void setAdministrator(String id, boolean isAdmin) throws CouldNotPerformException {
        if (!this.isLoggedIn()) throw new CouldNotPerformException("Please log in first!");
        
        try {
            ticketAuthenticatorWrapper = AuthenticationClientHandler.initServiceServerRequest(this.sessionKey, this.ticketAuthenticatorWrapper);

            LoginCredentialsChange loginCredentialsChange = LoginCredentialsChange.newBuilder()
                    .setId(id)
                    .setTicketAuthenticatorWrapper(this.ticketAuthenticatorWrapper)
                    .setAdmin(isAdmin)
                    .build();
         
            TicketAuthenticatorWrapper wrapper = CachedAuthenticationRemote.getRemote().setAdministrator(loginCredentialsChange).get();
            AuthenticationClientHandler.handleServiceServerResponse(this.sessionKey, this.ticketAuthenticatorWrapper, wrapper);    
            
            if (this.store.hasEntry(id))
                this.store.setAdmin(id, isAdmin);
        } catch (IOException ex) {
            this.logout();
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Decryption failed. You have been logged out for security reasons. Please log in again.");
        } catch (RejectedException | NotAvailableException ex) {
            throw ExceptionPrinter.printHistoryAndReturnThrowable(ex, LOGGER, LogLevel.ERROR);
        } catch (InterruptedException ex) {
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Action was interrupted.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();

            if (cause instanceof RejectedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((RejectedException) cause, LOGGER, LogLevel.ERROR);
            }

            if (cause instanceof PermissionDeniedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((PermissionDeniedException) cause, LOGGER, LogLevel.ERROR);
            }

            ExceptionPrinter.printHistory(cause, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Internal server error.", cause);
        }        
    }
}
