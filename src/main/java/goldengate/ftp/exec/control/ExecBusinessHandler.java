/**
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author
 * tags. See the COPYRIGHT.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3.0 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package goldengate.ftp.exec.control;

import java.io.File;

import goldengate.common.command.ReplyCode;
import goldengate.common.command.exception.CommandAbstractException;
import goldengate.common.command.exception.Reply426Exception;
import goldengate.common.command.exception.Reply502Exception;
import goldengate.common.command.exception.Reply504Exception;
import goldengate.common.future.GgFuture;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;
import goldengate.ftp.core.command.AbstractCommand;
import goldengate.ftp.core.command.FtpCommandCode;
import goldengate.ftp.core.control.BusinessHandler;
import goldengate.ftp.core.data.FtpTransfer;
import goldengate.ftp.core.exception.FtpNoFileException;
import goldengate.ftp.core.file.FtpFile;
import goldengate.ftp.core.session.FtpSession;
import goldengate.ftp.filesystembased.FilesystemBasedFtpAuth;
import goldengate.ftp.filesystembased.FilesystemBasedFtpRestart;
import goldengate.ftp.exec.config.AUTHUPDATE;
import goldengate.ftp.exec.exec.AbstractExecutor;
import goldengate.ftp.exec.exec.R66PreparedTransferExecutor;
import goldengate.ftp.exec.file.FileBasedAuth;
import goldengate.ftp.exec.file.FileBasedDir;

import openr66.database.DbConstant;
import openr66.database.DbSession;
import openr66.database.exception.OpenR66DatabaseNoConnectionError;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ExceptionEvent;

/**
 * BusinessHandler implementation that allows pre and post actions on any
 * operations and specifically on transfer operations
 *
 * @author Frederic Bregier
 *
 */
public class ExecBusinessHandler extends BusinessHandler {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(ExecBusinessHandler.class);

    /**
     * Associated DbSession
     */
    public DbSession dbSession = null;
    private boolean internalDb = false;



    /* (non-Javadoc)
     * @see goldengate.ftp.core.control.BusinessHandler#afterTransferDoneBeforeAnswer(goldengate.ftp.core.data.FtpTransfer)
     */
    @Override
    public void afterTransferDoneBeforeAnswer(FtpTransfer transfer)
            throws CommandAbstractException {
        // if Admin, do nothing
        if (getFtpSession() == null || getFtpSession().getAuth() == null) {
            return;
        }
        if (getFtpSession().getAuth().isAdmin()) {
            return;
        }
        if (getFtpSession().getReplyCode() != ReplyCode.REPLY_250_REQUESTED_FILE_ACTION_OKAY) {
            // Do nothing
            logger.debug("Which code: "+getFtpSession().getReplyCode().getMesg());
            return;
        }
        // if STOR like: get file (can be STOU) and execute external action
        switch (transfer.getCommand()) {
            case RETR:
                // nothing to do since All done
                break;
            case APPE:
            case STOR:
            case STOU:
                // execute the store command
                GgFuture futureCompletion = new GgFuture(true);
                String []args = new String[5];
                args[0] = getFtpSession().getAuth().getUser();
                args[1] = getFtpSession().getAuth().getAccount();
                args[2] = ((FilesystemBasedFtpAuth) getFtpSession().getAuth()).getBaseDirectory();
                FtpFile file;
                try {
                    file = transfer.getFtpFile();
                } catch (FtpNoFileException e1) {
                    // File cannot be sent
                    logger.error("PostExecution in Error for Transfer since No File found: {} " +
                            transfer.getStatus() + " {}",
                            transfer.getCommand(), transfer.getPath());
                    getFtpSession().setReplyCode(
                            ReplyCode.REPLY_426_CONNECTION_CLOSED_TRANSFER_ABORTED,
                            "PostExecution in Error for Transfer since No File found");
                    return;
                }
                try {
                    args[3] = file.getFile();
                    File newfile = new File(args[2]+args[3]);
                    if (! newfile.canRead()) {
                        // File cannot be sent
                        logger.error("PostExecution in Error for Transfer since File is not readable: {} " +
                                newfile.getAbsolutePath()+":"+newfile.canRead()+
                                " "+transfer.getStatus() + " {}",
                                transfer.getCommand(), transfer.getPath());
                        getFtpSession().setReplyCode(
                                ReplyCode.REPLY_426_CONNECTION_CLOSED_TRANSFER_ABORTED,
                                "Transfer done but force disconnection since an error occurs on PostOperation");
                        return;
                    }
                } catch (CommandAbstractException e1) {
                    // File cannot be sent
                    logger.error("PostExecution in Error for Transfer since No File found: {} " +
                            transfer.getStatus() + " {}",
                            transfer.getCommand(), transfer.getPath());
                    getFtpSession().setReplyCode(
                            ReplyCode.REPLY_426_CONNECTION_CLOSED_TRANSFER_ABORTED,
                            "Transfer done but force disconnection since an error occurs on PostOperation");
                    return;
                }
                args[4] = transfer.getCommand().toString();
                AbstractExecutor executor =
                    AbstractExecutor.createAbstractExecutor(args, true, futureCompletion);
                if (executor instanceof R66PreparedTransferExecutor){
                    ((R66PreparedTransferExecutor)executor).setDbsession(dbSession);
                }
                executor.run();
                try {
                    futureCompletion.await();
                } catch (InterruptedException e) {
                }
                if (futureCompletion.isSuccess()) {
                    // All done
                } else {
                    // File cannot be sent
                    logger.error("PostExecution in Error for Transfer: {} " +
                            transfer.getStatus() + " {} \n   "+(futureCompletion.getCause() != null?
                                    futureCompletion.getCause().getMessage():"Internal error of PostExecution"),
                            transfer.getCommand(), transfer.getPath());
                    getFtpSession().setReplyCode(
                            ReplyCode.REPLY_426_CONNECTION_CLOSED_TRANSFER_ABORTED,
                            "Transfer done but force disconnection since an error occurs on PostOperation");
                }
                break;
            default:
                // nothing to do
        }
    }

    @Override
    public void afterTransferDone(FtpTransfer transfer) {
        // Do nothing
    }

    @Override
    public void afterRunCommandKo(CommandAbstractException e) {
        logger.warn("GBBH: AFTKO: {} {}", getFtpSession(), e.getMessage());
    }

    @Override
    public void afterRunCommandOk() throws CommandAbstractException {
        // nothing to do since it is only Command and not transfer
    }

    @Override
    public void beforeRunCommand() throws CommandAbstractException {
        // if Admin, do nothing
        if (getFtpSession() == null || getFtpSession().getAuth() == null) {
            return;
        }
        if (getFtpSession().getAuth().isAdmin()) {
            return;
        }
        FtpCommandCode code = getFtpSession().getCurrentCommand().getCode();
        switch (code) {
            case APPE:
            case STOR:
            case STOU:
                if (!AbstractExecutor.isValidOperation(true)) {
                    throw new Reply504Exception("STORe like operations are not allowed");
                }
                // nothing to do now
                break;
            case RETR:
                if (!AbstractExecutor.isValidOperation(false)) {
                    throw new Reply504Exception("RETRieve like operations are not allowed");
                }
                // execute the external retrieve command before the execution of RETR
                GgFuture futureCompletion = new GgFuture(true);
                String []args = new String[5];
                args[0] = getFtpSession().getAuth().getUser();
                args[1] = getFtpSession().getAuth().getAccount();
                args[2] = ((FilesystemBasedFtpAuth) getFtpSession().getAuth()).getBaseDirectory();
                String filename = getFtpSession().getCurrentCommand().getArg();
                FtpFile file = getFtpSession().getDir().setFile(filename, false);
                args[3] = file.getFile();
                args[4] = code.toString();
                AbstractExecutor executor =
                    AbstractExecutor.createAbstractExecutor(args, false, futureCompletion);
                if (executor instanceof R66PreparedTransferExecutor){
                    ((R66PreparedTransferExecutor)executor).setDbsession(dbSession);
                }
                executor.run();
                try {
                    futureCompletion.await();
                } catch (InterruptedException e) {
                }
                if (futureCompletion.isSuccess()) {
                    // File should be ready
                    if (! file.canRead()) {
                        logger.error("PreExecution in Error for Transfer since " +
                                "File downloaded but not ready to be retrieved: {} " +
                                " {} \n   "+(futureCompletion.getCause() != null?
                                        futureCompletion.getCause().getMessage():
                                            "File downloaded but not ready to be retrieved"),
                                            args[4], args[3]);
                        throw new Reply426Exception("File downloaded but not ready to be retrieved");
                    }
                } else {
                    // File cannot be retrieved
                    logger.error("PreExecution in Error for Transfer since " +
                            "File cannot be prepared to be retrieved: {} " +
                            " {} \n   "+(futureCompletion.getCause() != null?
                                    futureCompletion.getCause().getMessage():
                                        "File cannot be prepared to be retrieved"),
                                        args[4], args[3]);
                    throw new Reply426Exception("File cannot be prepared to be retrieved");
                }
                break;
            default:
                // nothing to do
        }
    }

    @Override
    protected void cleanSession() {
    }

    @Override
    public void exceptionLocalCaught(ExceptionEvent e) {
    }

    @Override
    public void executeChannelClosed() {
        if (AbstractExecutor.useDatabase){
            if (! internalDb) {
                if (dbSession != null) {
                    dbSession.disconnect();
                    dbSession = null;
                }
            }
        }
    }

    @Override
    public void executeChannelConnected(Channel channel) {
        if (AbstractExecutor.useDatabase) {
            try {
                dbSession = new DbSession(DbConstant.admin, false);
            } catch (OpenR66DatabaseNoConnectionError e1) {
                logger.warn("Database not ready due to {}", e1.getMessage());
                dbSession = DbConstant.admin.session;
                internalDb = true;
            }
        }
    }

    @Override
    public FileBasedAuth getBusinessNewAuth() {
        return new FileBasedAuth(getFtpSession());
    }

    @Override
    public FileBasedDir getBusinessNewDir() {
        return new FileBasedDir(getFtpSession());
    }

    @Override
    public FilesystemBasedFtpRestart getBusinessNewRestart() {
        return new FilesystemBasedFtpRestart(getFtpSession());
    }

    @Override
    public String getHelpMessage(String arg) {
        return "This FTP server is only intend as a Gateway. RETRieve actions may be unallowed.\n"
                + "This FTP server refers to RFC 959, 775, 2389, 2428, 3659 and supports XCRC, XMD5 and XSHA1 commands.\n"
                + "XCRC, XMD5 and XSHA1 take a simple filename as argument and return \"250 digest-value is the digest of filename\".";
    }

    @Override
    public String getFeatMessage() {
        StringBuilder builder = new StringBuilder("Extensions supported:");
        builder.append('\n');
        builder.append(getDefaultFeatMessage());
        builder.append('\n');
        builder.append(FtpCommandCode.SITE.name());
        builder.append(' ');
        builder.append("AUTHUPDATE");
        builder.append("\nEnd");
        return builder.toString();
    }

    @Override
    public String getOptsMessage(String[] args) throws CommandAbstractException {
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase(FtpCommandCode.MLST.name()) ||
                    args[0].equalsIgnoreCase(FtpCommandCode.MLSD.name())) {
                return getMLSxOptsMessage(args);
            }
            throw new Reply502Exception("OPTS not implemented for " + args[0]);
        }
        throw new Reply502Exception("OPTS not implemented");
    }

    /* (non-Javadoc)
     * @see goldengate.ftp.core.control.BusinessHandler#getSpecializedSiteCommand(goldengate.ftp.core.session.FtpSession, java.lang.String)
     */
    @Override
    public AbstractCommand getSpecializedSiteCommand(FtpSession session,
            String line) {
        if (getFtpSession() == null || getFtpSession().getAuth() == null) {
            return null;
        }
        if (!session.getAuth().isAdmin()) {
            return null;
        }
        String newline = line;
        if (newline == null) {
            return null;
        }
        String command = null;
        String arg = null;
        if (newline.indexOf(' ') == -1) {
            command = newline;
            arg = null;
        } else {
            command = newline.substring(0, newline.indexOf(' '));
            arg = newline.substring(newline.indexOf(' ') + 1);
            if (arg.length() == 0) {
                arg = null;
            }
        }
        String COMMAND = command.toUpperCase();
        if (! COMMAND.equals("AUTHUPDATE")) {
            return null;
        }
        AbstractCommand abstractCommand = new AUTHUPDATE();
        abstractCommand.setArgs(session, COMMAND, arg, FtpCommandCode.SITE);
        return abstractCommand;
    }
}
