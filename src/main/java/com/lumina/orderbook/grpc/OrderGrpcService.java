package com.lumina.orderbook.grpc;

import com.lumina.orderbook.config.ConditionalOnRole;
import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.engine.TimeInForce;
import com.lumina.orderbook.gateway.GatewayException;
import com.lumina.orderbook.gateway.OrderGateway;
import com.lumina.orderbook.gateway.PlaceOutcome;
import com.lumina.orderbook.gateway.PlaceRequest;
import com.lumina.orderbook.view.BookView;
import com.lumina.orderbook.view.OrderView;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/** gRPC front for the same {@link OrderGateway} the REST API uses. */
@Service
@ConditionalOnRole("api")
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private final OrderGateway gateway;

    public OrderGrpcService(OrderGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void placeOrder(com.lumina.orderbook.grpc.PlaceOrderRequest request, StreamObserver<OrderResponse> out) {
        respond(out, () -> {
            String account = requireAccount();
            PlaceRequest placeRequest = new PlaceRequest(
                    request.getSymbol(),
                    side(request.getSide()),
                    type(request.getType()),
                    tif(request.getTimeInForce()),
                    request.getPostOnly(),
                    request.getPrice().isBlank() ? null : decimal(request.getPrice(), "price"),
                    decimal(request.getQuantity(), "quantity"));
            PlaceOutcome outcome = gateway.place(account, placeRequest, request.getIdempotencyKey());
            return switch (outcome) {
                case PlaceOutcome.Placed placed -> toResponse(placed.order());
                case PlaceOutcome.Duplicate dup -> dup.existing().map(OrderGrpcService::toResponse).orElseThrow(() ->
                        GatewayException.conflict("DUPLICATE_PENDING", "order already submitted; retry the lookup shortly"));
                case PlaceOutcome.Rejected rejected -> throw GatewayException.invalid(
                        rejected.reason().name(), "order rejected: " + rejected.reason());
            };
        });
    }

    @Override
    public void cancelOrder(CancelOrderRequest request, StreamObserver<OrderResponse> out) {
        respond(out, () -> {
            UUID id;
            try {
                id = UUID.fromString(request.getOrderId());
            } catch (IllegalArgumentException e) {
                throw GatewayException.invalid("INVALID_ORDER_ID", "order_id must be a UUID");
            }
            return toResponse(gateway.cancel(requireAccount(), id));
        });
    }

    @Override
    public void getOrderBook(OrderBookRequest request, StreamObserver<OrderBookResponse> out) {
        respond(out, () -> {
            BookView book = gateway.orderBook(request.getSymbol(), request.getDepth() > 0 ? request.getDepth() : 10);
            OrderBookResponse.Builder b = OrderBookResponse.newBuilder()
                    .setSymbol(book.symbol())
                    .setSequence(book.sequence());
            book.bids().forEach(l -> b.addBids(level(l)));
            book.asks().forEach(l -> b.addAsks(level(l)));
            return b.build();
        });
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static <T> void respond(StreamObserver<T> out, Supplier<T> call) {
        try {
            out.onNext(call.get());
            out.onCompleted();
        } catch (io.grpc.StatusRuntimeException e) {
            out.onError(e);
        } catch (GatewayException e) {
            out.onError(status(e).withDescription(e.code() + ": " + e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription("internal error").asRuntimeException());
        }
    }

    private static Status status(GatewayException e) {
        return switch (e.kind()) {
            case INVALID -> Status.INVALID_ARGUMENT;
            case NOT_FOUND -> Status.NOT_FOUND;
            case CONFLICT -> Status.ALREADY_EXISTS;
            case RATE_LIMITED -> Status.RESOURCE_EXHAUSTED;
            case TIMEOUT -> Status.DEADLINE_EXCEEDED;
            case UNAVAILABLE -> Status.UNAVAILABLE;
        };
    }

    private static String requireAccount() {
        String account = ApiKeyServerInterceptor.ACCOUNT.get();
        if (account == null) {
            throw new io.grpc.StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("missing or invalid x-api-key"));
        }
        return account;
    }

    private static BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw GatewayException.invalid("INVALID_" + field.toUpperCase(), field + " is not a decimal number");
        }
    }

    private static Side side(com.lumina.orderbook.grpc.Side side) {
        return switch (side) {
            case SIDE_BUY -> Side.BUY;
            case SIDE_SELL -> Side.SELL;
            default -> throw GatewayException.invalid("INVALID_SIDE", "side is required");
        };
    }

    private static OrderType type(com.lumina.orderbook.grpc.OrderType type) {
        return switch (type) {
            case ORDER_TYPE_LIMIT -> OrderType.LIMIT;
            case ORDER_TYPE_MARKET -> OrderType.MARKET;
            default -> throw GatewayException.invalid("INVALID_TYPE", "order type is required");
        };
    }

    private static TimeInForce tif(com.lumina.orderbook.grpc.TimeInForce tif) {
        return switch (tif) {
            case TIME_IN_FORCE_IOC -> TimeInForce.IOC;
            case TIME_IN_FORCE_FOK -> TimeInForce.FOK;
            default -> TimeInForce.GTC;
        };
    }

    private static OrderBookLevel level(BookView.Level level) {
        return OrderBookLevel.newBuilder()
                .setPrice(level.price().toPlainString())
                .setQuantity(level.quantity().toPlainString())
                .build();
    }

    private static OrderResponse toResponse(OrderView o) {
        OrderResponse.Builder b = OrderResponse.newBuilder()
                .setId(o.id().toString())
                .setSymbol(o.symbol())
                .setStatus(o.status().name())
                .setCreatedAt(o.createdAt().toString());
        if (o.side() != null) {
            b.setSide(o.side() == Side.BUY ? com.lumina.orderbook.grpc.Side.SIDE_BUY : com.lumina.orderbook.grpc.Side.SIDE_SELL)
                    .setType(o.type() == OrderType.LIMIT
                            ? com.lumina.orderbook.grpc.OrderType.ORDER_TYPE_LIMIT
                            : com.lumina.orderbook.grpc.OrderType.ORDER_TYPE_MARKET)
                    .setTimeInForce(switch (o.timeInForce()) {
                        case GTC -> com.lumina.orderbook.grpc.TimeInForce.TIME_IN_FORCE_GTC;
                        case IOC -> com.lumina.orderbook.grpc.TimeInForce.TIME_IN_FORCE_IOC;
                        case FOK -> com.lumina.orderbook.grpc.TimeInForce.TIME_IN_FORCE_FOK;
                    })
                    .setQuantity(o.quantity().toPlainString())
                    .setFilledQuantity(o.filledQuantity().toPlainString())
                    .setRemainingQuantity(o.remainingQuantity().toPlainString());
        }
        if (o.price() != null) {
            b.setPrice(o.price().toPlainString());
        }
        if (o.rejectReason() != null) {
            b.setRejectReason(o.rejectReason().name());
        }
        return b.build();
    }
}
