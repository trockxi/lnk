package io.lnk.remoting.netty;

import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lnk.api.protocol.ProtocolFactory;
import io.lnk.api.protocol.ProtocolFactorySelector;
import io.lnk.api.utils.LnkThreadFactory;
import io.lnk.remoting.CommandProcessor;
import io.lnk.remoting.Pair;
import io.lnk.remoting.RemotingCallback;
import io.lnk.remoting.ReplyFuture;
import io.lnk.remoting.exception.RemotingSendRequestException;
import io.lnk.remoting.exception.RemotingTimeoutException;
import io.lnk.remoting.protocol.CommandCode;
import io.lnk.remoting.protocol.RemotingCommand;
import io.lnk.remoting.utils.RemotingUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

/**
 * @author 刘飞 E-mail:liufei_it@126.com
 *
 * @version 1.0.0
 * @since 2017年5月19日 下午8:45:47
 */
public abstract class NettyAbstractRemotingService {
    protected final Logger log = LoggerFactory.getLogger(getClass().getSimpleName());
    protected final ConcurrentHashMap<Long, ReplyFuture> replies;
    protected final HashMap<Integer, Pair<CommandProcessor, ExecutorService>> processors;
    protected Pair<CommandProcessor, ExecutorService> defaultCommandProcessor;
    protected final ProtocolFactorySelector protocolFactorySelector;
    private final ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

    public NettyAbstractRemotingService(final ProtocolFactorySelector protocolFactorySelector) {
        super();
        this.protocolFactorySelector = protocolFactorySelector;
        replies = new ConcurrentHashMap<Long, ReplyFuture>(256);
        processors = new HashMap<Integer, Pair<CommandProcessor, ExecutorService>>(64);
        scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(2, LnkThreadFactory.newThreadFactory("RemotingReply-%d", false), new CallerRunsPolicy() {
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                log.info("rejectedExecution Thread resource work out.");
                super.rejectedExecution(r, e);
            }
        });
        scheduledThreadPoolExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                final List<ReplyFuture> replyFutures = new LinkedList<ReplyFuture>();
                Iterator<Entry<Long, ReplyFuture>> it = replies.entrySet().iterator();
                while (it.hasNext()) {
                    Entry<Long, ReplyFuture> next = it.next();
                    ReplyFuture replyFuture = next.getValue();
                    if (replyFuture.isTimeout(replyFuture.getTimeoutMillis() + 1000L)) {
                        it.remove();
                        replyFutures.add(replyFuture);
                        log.warn("remove timeout command " + replyFuture);
                    }
                }

                for (ReplyFuture replyFuture : replyFutures) {
                    if (!replyFuture.isAsyncCallback()) {
                        continue;
                    }
                    try {
                        replyFuture.invokeCallback();
                    } catch (Throwable e) {
                        log.warn("invoke ReplyFuture callback Error.", e);
                    }
                }
            }
        }, 5l, 1l, TimeUnit.SECONDS);
    }

    protected void commandProcessor(final ChannelHandlerContext ctx, RemotingCommand command) throws Exception {
        if (command == null) {
            return;
        }
        final RemotingCommand cmd = command;
        if (cmd.isReply()) {
            replyCommand(ctx, cmd);
            return;
        }
        receivedCommand(ctx, cmd);
    }

    private void receivedCommand(final ChannelHandlerContext ctx, final RemotingCommand cmd) {
        final Pair<CommandProcessor, ExecutorService> commandProcessorPair = this.processors.get(cmd.getCode());
        final Pair<CommandProcessor, ExecutorService> pair = (null == commandProcessorPair) ? this.defaultCommandProcessor : commandProcessorPair;
        final long opaque = cmd.getOpaque();
        final ProtocolFactory protocolFactory = protocolFactorySelector.select(cmd.getProtocol());
        if (pair == null) {
            String message = " command code " + cmd.getCode() + " not supported";
            final RemotingCommand response = RemotingCommand.replyCommand(cmd, CommandCode.COMMAND_CODE_NOT_SUPPORTED);
            response.setBody(protocolFactory.encode(message));
            ctx.writeAndFlush(response);
            log.error(RemotingUtils.parseChannelRemoteAddr(ctx.channel()) + message);
            return;
        }
        final CommandProcessor commandProcessor = pair.getKey();
        if (commandProcessor.tryAcquireFailure(3000L)) {
            final RemotingCommand response = RemotingCommand.replyCommand(cmd, CommandCode.SYSTEM_BUSY);
            response.setBody(protocolFactory.encode("system busy, start flow control for a while"));
            ctx.writeAndFlush(response);
            return;
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    final RemotingCommand response = commandProcessor.processCommand(cmd);
                    if (!cmd.isOneway()) {
                        if (response != null) {
                            response.setOpaque(opaque);
                            response.setReply();
                            try {
                                ctx.writeAndFlush(response);
                            } catch (Throwable e) {
                                log.error("process command over, but reply Error.", e);
                            }
                        }
                    }
                } catch (Throwable e) {
                    log.error("process command Error.", e);
                    if (!cmd.isOneway()) {
                        final RemotingCommand response = RemotingCommand.replyCommand(cmd, CommandCode.SYSTEM_ERROR);
                        response.setBody(protocolFactory.encode(RemotingUtils.exceptionToString(e)));
                        ctx.writeAndFlush(response);
                    }
                } finally {
                    commandProcessor.release();
                }
            }
        };
        try {
            pair.getValue().submit(task);
        } catch (RejectedExecutionException e) {
            log.warn(RemotingUtils.parseChannelRemoteAddr(ctx.channel()) + ", too many command and system thread pool busy, RejectedExecutionException " + pair.getValue().toString()
                    + " command code: " + cmd.getCode());
            if (!cmd.isOneway()) {
                final RemotingCommand response = RemotingCommand.replyCommand(cmd, CommandCode.SYSTEM_BUSY);
                response.setBody(protocolFactory.encode("system busy, start flow control for a while"));
                ctx.writeAndFlush(response);
            }
        }
    }
    
    protected abstract ExecutorService getCallbackExecutor();

    protected void replyCommand(final ChannelHandlerContext ctx, RemotingCommand cmd) {
        final long opaque = cmd.getOpaque();
        final ReplyFuture replyFuture = replies.get(opaque);
        if (replyFuture == null) {
            log.warn("receive command, but not matched any command, " + RemotingUtils.parseChannelRemoteAddr(ctx.channel()));
            return;
        }
        replyFuture.setResponse(cmd);
        replies.remove(opaque);
        if (replyFuture.isAsyncCallback()) {
            invokeCallback(replyFuture);
        } else {
            replyFuture.setReply(cmd);
        }
    }
    
    private void invokeCallback(final ReplyFuture replyFuture) {
        boolean runInThisThread = false;
        ExecutorService executor = this.getCallbackExecutor();
        if (executor != null) {
            try {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            replyFuture.invokeCallback();
                        } catch (Throwable e) {
                            log.warn("invoke callback in CallbackExecutor Error.", e);
                        }
                    }
                });
            } catch (Exception e) {
                runInThisThread = true;
                log.warn("invoke callback in CallbackExecutor Error, maybe executor busy", e);
            }
        } else {
            runInThisThread = true;
        }
        if (runInThisThread) {
            try {
                replyFuture.invokeCallback();
            } catch (Throwable e) {
                log.warn("invoke callback Error.", e);
            }
        }
    }

    protected RemotingCommand __invokeSync(final Channel channel, final RemotingCommand request, final long timeoutMillis) throws InterruptedException, RemotingSendRequestException, RemotingTimeoutException {
        final long opaque = request.getOpaque();
        try {
            final ReplyFuture replyFuture = new ReplyFuture(opaque, timeoutMillis);
            this.replies.put(opaque, replyFuture);
            final SocketAddress addr = channel.remoteAddress();
            channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if (channelFuture.isSuccess()) {
                        replyFuture.setSent(true);
                        return;
                    }
                    replyFuture.setSent(false);
                    replies.remove(opaque);
                    replyFuture.setCause(channelFuture.cause());
                    replyFuture.setReply(null);
                    log.warn("send command to channel <" + addr + "> Error.");
                }
            });
            RemotingCommand responseCommand = replyFuture.waitFor(timeoutMillis);
            if (null == responseCommand) {
                if (replyFuture.isSent()) {
                    throw new RemotingTimeoutException(RemotingUtils.parseSocketAddressAddr(addr), timeoutMillis, replyFuture.getCause());
                }
                throw new RemotingSendRequestException(RemotingUtils.parseSocketAddressAddr(addr), replyFuture.getCause());
            }

            return responseCommand;
        } finally {
            this.replies.remove(opaque);
        }
    }

    protected void __invokeAsync(final Channel channel, final RemotingCommand request, final long timeoutMillis, final RemotingCallback callback) throws RemotingSendRequestException {
        try {
            final long opaque = request.getOpaque();
            final ReplyFuture replyFuture = new ReplyFuture(opaque, timeoutMillis);
            replyFuture.setCallback(callback);
            this.replies.put(opaque, replyFuture);
            channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if (channelFuture.isSuccess()) {
                        replyFuture.setSent(true);
                        return;
                    }
                    replyFuture.setSent(false);
                    replyFuture.setReply(null);
                    replies.remove(opaque);
                    replyFuture.setCause(channelFuture.cause());
                    callback.onComplete(replyFuture);
                    log.warn("send command to channel <{}> Error.", RemotingUtils.parseChannelRemoteAddr(channel));
                }
            });
        } catch (Throwable e) {
            String remoteAddress = RemotingUtils.parseChannelRemoteAddr(channel);
            log.warn("send command to channel <" + remoteAddress + "> Error.", e);
            throw new RemotingSendRequestException(remoteAddress, e);
        }
    }

    protected void __invokeOneway(final Channel channel, final RemotingCommand request) throws RemotingSendRequestException {
        try {
            request.setOneway();
            channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if (!channelFuture.isSuccess()) {
                        log.warn("send command to channel <" + channel.remoteAddress() + "> Error.");
                    }
                }
            });
        } catch (Throwable e) {
            String remoteAddress = RemotingUtils.parseChannelRemoteAddr(channel);
            log.warn("send command to channel <" + remoteAddress + "> Error.");
            throw new RemotingSendRequestException(remoteAddress, e);
        }
    }
}
