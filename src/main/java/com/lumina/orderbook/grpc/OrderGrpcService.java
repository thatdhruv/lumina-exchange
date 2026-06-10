package com.lumina.orderbook.grpc;

import com.lumina.orderbook.api.dto.PlaceOrderRequest;
import com.lumina.orderbook.domain.Order;
import com.lumina.orderbook.domain.OrderSide;
import com.lumina.orderbook.domain.OrderType;
import com.lumina.orderbook.engine.OrderBookSnapshot;
import com.lumina.orderbook.engine.PriceLevel;
import com.lumina.orderbook.service.OrderService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.math.BigDecimal;

@GrpcService
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private final OrderService orderService;

    public OrderGrpcService(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void placeOrder(
            com.lumina.orderbook.grpc.PlaceOrderRequest request,
            StreamObserver<PlaceOrderResponse> responseObserver) {
        Order order = orderService.placeOrder(toRestRequest(request));
        responseObserver.onNext(toGrpcResponse(order));
        responseObserver.onCompleted();
    }

    @Override
    public void getOrderBook(
            OrderBookRequest request,
            StreamObserver<OrderBookResponse> responseObserver) {
        OrderBookSnapshot snapshot = orderService.getOrderBook(request.getSymbol());
        OrderBookResponse.Builder builder = OrderBookResponse.newBuilder()
                .setSymbol(request.getSymbol());
        snapshot.bids().forEach(level -> builder.addBids(toGrpcLevel(level)));
        snapshot.asks().forEach(level -> builder.addAsks(toGrpcLevel(level)));
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    private PlaceOrderRequest toRestRequest(com.lumina.orderbook.grpc.PlaceOrderRequest request) {
        BigDecimal price = request.getPrice().isBlank() ? null : new BigDecimal(request.getPrice());
        return new PlaceOrderRequest(
                request.getSymbol(),
                toDomainSide(request.getSide()),
                toDomainType(request.getType()),
                price,
                new BigDecimal(request.getQuantity()));
    }

    private PlaceOrderResponse toGrpcResponse(Order order) {
        return PlaceOrderResponse.newBuilder()
                .setId(order.getId().toString())
                .setSymbol(order.getSymbol())
                .setSide(toGrpcSide(order.getSide()))
                .setType(toGrpcType(order.getType()))
                .setPrice(order.getPrice().toPlainString())
                .setQuantity(order.getQuantity().toPlainString())
                .setStatus(order.getStatus().name())
                .setCreatedAt(order.getCreatedAt().toString())
                .build();
    }

    private OrderBookLevel toGrpcLevel(PriceLevel level) {
        return OrderBookLevel.newBuilder()
                .setPrice(level.price().toPlainString())
                .setQuantity(level.quantity().toPlainString())
                .build();
    }

    private OrderSide toDomainSide(Side side) {
        return switch (side) {
            case BUY -> OrderSide.BUY;
            case SELL -> OrderSide.SELL;
            default -> throw new IllegalArgumentException("Side is required");
        };
    }

    private OrderType toDomainType(OrderTypeEnum type) {
        return switch (type) {
            case LIMIT -> OrderType.LIMIT;
            case MARKET -> OrderType.MARKET;
            default -> throw new IllegalArgumentException("Order type is required");
        };
    }

    private Side toGrpcSide(OrderSide side) {
        return side == OrderSide.BUY ? Side.BUY : Side.SELL;
    }

    private OrderTypeEnum toGrpcType(OrderType type) {
        return type == OrderType.LIMIT ? OrderTypeEnum.LIMIT : OrderTypeEnum.MARKET;
    }
}
