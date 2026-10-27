package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** AI envelope: eventId at root; existing order fields and account/user IDs in payload. */
public class OrderRequestCodec {
    private final JsonMapper json;
    public OrderRequestCodec(JsonMapper json) { this.json=json; }
    public String encode(OrderRequestMessage m) {
        ObjectNode root=json.createObjectNode();
        root.put("eventId",m.eventId()); root.put("correlationId",m.correlationId());
        root.put("source",m.source().name()); root.put("requestedAt",m.requestedAt().toString());
        ObjectNode payload=json.valueToTree(m.request());
        payload.put("account_id",m.accountId()); payload.put("user_id",m.userId());
        root.set("payload",payload);
        String body=json.writeValueAsString(root);
        if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1048576)
            throw new com.stock_spoon.river_be.order.exception.OrderException("주문 메시지가 SQS 허용 크기를 초과합니다.");
        return body;
    }
    public OrderRequestMessage decode(String body,Instant sentAt) {
        var root=json.readTree(body);
        if(!root.isObject() || !(root.get("payload") instanceof ObjectNode original)) throw new IllegalArgumentException("Missing order payload");
        var payload=original.deepCopy();
        var account=payload.remove("account_id"); var user=payload.remove("user_id");
        if(account==null || user==null || !account.isIntegralNumber() || !user.isIntegralNumber()
                || !account.canConvertToLong() || !user.canConvertToLong()) throw new IllegalArgumentException("Invalid order identity");
        String event=root.path("eventId").asText("");
        String correlation=root.hasNonNull("correlationId")?root.get("correlationId").asText():event;
        var source=root.hasNonNull("source")?Order.Source.valueOf(root.get("source").asText()):Order.Source.AI;
        Instant requested=root.hasNonNull("requestedAt")?Instant.parse(root.get("requestedAt").asText()):sentAt;
        if(requested==null || sentAt==null || requested.isAfter(sentAt.plusSeconds(5))) throw new IllegalArgumentException("Invalid request timestamp");
        return new OrderRequestMessage(event,correlation,user.longValue(),account.longValue(),source,requested,
                json.treeToValue(payload,OrderCreateRequest.class));
    }
}
