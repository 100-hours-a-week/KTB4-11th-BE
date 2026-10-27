package com.stock_spoon.river_be.order.reservation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.json.JsonMapper;

public class RedisOrderReservationStore implements OrderReservationStore {
    private static final String PREFIX="order:temporary:";
    private static final String ACCOUNTS="order:temporary:accounts";
    private static final DefaultRedisScript<Long> PUBLISHED=new DefaultRedisScript<>(
            "if redis.call('HGET',KEYS[1],ARGV[1]) == ARGV[2] then return redis.call('HSET',KEYS[1],ARGV[1],ARGV[3]) else return -1 end",Long.class);
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    public RedisOrderReservationStore(StringRedisTemplate redis,JsonMapper json) { this.redis=redis; this.json=json; }
    private String key(long id) { return PREFIX+"{"+id+"}"; }
    public List<Reservation> list(long accountId) {
        return redis.<String,String>opsForHash().values(key(accountId)).stream().map(s->json.readValue(s,Reservation.class)).toList();
    }
    public void add(Reservation r) {
        // Register first: a crash can leave an empty account entry, never an undiscoverable reservation.
        redis.opsForSet().add(ACCOUNTS,Long.toString(r.message().accountId()));
        if(!Boolean.TRUE.equals(redis.opsForHash().putIfAbsent(key(r.message().accountId()),r.message().eventId(),json.writeValueAsString(r))))
            throw new IllegalStateException("Concurrent reservation change; retry with the same eventId");
    }
    public void remove(long accountId,String eventId) {
        redis.opsForHash().delete(key(accountId),eventId);
        // All add/remove callers hold the same DB account lock, including index maintenance.
        if(redis.opsForHash().size(key(accountId))==0) redis.opsForSet().remove(ACCOUNTS,Long.toString(accountId));
    }
    public void published(Reservation r,Instant at) {
        redis.execute(PUBLISHED,List.of(key(r.message().accountId())),r.message().eventId(),json.writeValueAsString(r),
                json.writeValueAsString(new Reservation(r.message(),r.amount(),at)));
    }
    // [계좌 비활성화 추가] 계좌 예약 해시와 스캔 인덱스를 함께 제거한다. 비활성 계좌에는 새 예약이 추가되지 않는다.
    @Override public void removeAll(long accountId) {
        redis.delete(key(accountId));
        redis.opsForSet().remove(ACCOUNTS,Long.toString(accountId));
    }
    public List<Long> accounts() {
        var result=new ArrayList<Long>();
        // Keyed SSCAN works across cluster slots; keyspace SCAN may miss cluster nodes.
        try(var cursor=redis.opsForSet().scan(ACCOUNTS,ScanOptions.scanOptions().count(100).build())) {
            while(cursor.hasNext()) {
                result.add(Long.parseLong(cursor.next()));
            }
        }
        return result;
    }
}
