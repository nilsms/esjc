package lt.msemys.esjc.tcp.handler;

import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lt.msemys.esjc.node.NodeEndPoints;
import lt.msemys.esjc.operation.InspectionResult;
import lt.msemys.esjc.operation.manager.OperationItem;
import lt.msemys.esjc.operation.manager.OperationManager;
import lt.msemys.esjc.subscription.manager.SubscriptionItem;
import lt.msemys.esjc.subscription.manager.SubscriptionManager;
import lt.msemys.esjc.tcp.TcpPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Consumer;

public class OperationHandler extends SimpleChannelInboundHandler<TcpPackage> {
    private static final Logger logger = LoggerFactory.getLogger(OperationHandler.class);

    private final OperationManager operationManager;
    private final SubscriptionManager subscriptionManager;
    private Optional<Consumer<TcpPackage>> badRequestConsumer;
    private Optional<Consumer<NodeEndPoints>> reconnectConsumer;

    public OperationHandler(OperationManager operationManager, SubscriptionManager subscriptionManager) {
        this.operationManager = operationManager;
        this.subscriptionManager = subscriptionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TcpPackage msg) throws Exception {
        switch (msg.command) {
            case BadRequest:
                if (msg.correlationId == null) {
                    badRequestConsumer.ifPresent(c -> c.accept(msg));
                    break;
                }
            default:
                Optional<OperationItem> operationItem = operationManager.getActiveOperation(msg.correlationId);

                if (operationItem.isPresent()) {
                    OperationItem item = operationItem.get();

                    InspectionResult result = item.operation.inspect(msg);

                    logger.debug("HandleTcpPackage OPERATION DECISION {} ({}), {}", result.decision, result.description, item);

                    switch (result.decision) {
                        case DoNothing:
                            break;
                        case EndOperation:
                            operationManager.removeOperation(item);
                            break;
                        case Retry:
                            operationManager.scheduleOperationRetry(item);
                            break;
                        case Reconnect:
                            reconnectConsumer.ifPresent(c -> c.accept(new NodeEndPoints(result.address.orElse(null), result.secureAddress.orElse(null))));
                            operationManager.scheduleOperationRetry(item);
                            break;
                        default:
                            throw new ChannelException(String.format("Unknown InspectionDecision: {}", result.decision));
                    }
                    operationManager.scheduleWaitingOperations(ctx.channel());
                } else {
                    Optional<SubscriptionItem> subscriptionItem = subscriptionManager.getActiveSubscription(msg.correlationId);

                    if (subscriptionItem.isPresent()) {
                        SubscriptionItem item = subscriptionItem.get();

                        InspectionResult result = item.operation.inspect(msg);

                        logger.debug("HandleTcpPackage SUBSCRIPTION DECISION {} ({}), {}", result.decision, result.description, item);

                        switch (result.decision) {
                            case DoNothing:
                                break;
                            case EndOperation:
                                subscriptionManager.removeSubscription(item);
                                break;
                            case Retry:
                                subscriptionManager.scheduleSubscriptionRetry(item);
                                break;
                            case Reconnect:
                                reconnectConsumer.ifPresent(c -> c.accept(new NodeEndPoints(result.address.orElse(null), result.secureAddress.orElse(null))));
                                subscriptionManager.scheduleSubscriptionRetry(item);
                                break;
                            case Subscribed:
                                item.isSubscribed = true;
                                break;
                            default:
                                throw new ChannelException("Unknown InspectionDecision: " + result.decision);
                        }
                    } else {
                        logger.debug("HandleTcpPackage UNMAPPED PACKAGE with CorrelationId {}, Command: {}", msg.correlationId, msg.command);
                    }
                }
        }
    }

    public OperationHandler whenBadRequest(Consumer<TcpPackage> consumer) {
        badRequestConsumer = Optional.of(consumer);
        return this;
    }

    public OperationHandler whenReconnect(Consumer<NodeEndPoints> consumer) {
        reconnectConsumer = Optional.of(consumer);
        return this;
    }

}