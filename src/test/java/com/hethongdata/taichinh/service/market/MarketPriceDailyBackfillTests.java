package com.hethongdata.taichinh.service.market;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.MarketPriceEntity;
import com.hethongdata.taichinh.entity.ingestion.*;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.*;
import com.hethongdata.taichinh.repository.jpa.market.MarketPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MarketPriceDailyBackfillTests {
    @Test void largeDailyBatchesStayIdempotentSelectPreferredSourceAndRejectStaleReplay() {
        var json=new ObjectMapper();var versions=mock(DataVersionJpaRepository.class);
        var raws=mock(RawPayloadJpaRepository.class);var runs=mock(IngestionRunJpaRepository.class);
        var sources=mock(DataSourceJpaRepository.class);var securities=mock(SecurityJpaRepository.class);
        var prices=mock(MarketPriceJpaRepository.class);
        var writes=new MarketPriceWorkflowPersistenceService(versions,raws,runs,sources,securities,prices,new MarketPricePayloadParser(),json);
        var security=SecurityEntity.create(UUID.randomUUID(),"FPT","HOSE","STOCK",null,"VND",null,null,null,null,true,true);
        ReflectionTestUtils.setField(security,"id",UUID.randomUUID());
        when(securities.findById(security.getId())).thenReturn(Optional.of(security));
        when(securities.findByIdForUpdate(security.getId())).thenReturn(Optional.of(security));
        var direct=DataSourceEntity.create("VNDIRECT","VNDirect","API",null,"vndirect",false,"UNKNOWN");
        var preferred=DataSourceEntity.create("VNSTOCK","VNStock","API",null,"vnstock",false,"UNKNOWN");
        ReflectionTestUtils.setField(direct,"id",11L);ReflectionTestUtils.setField(direct,"priority",(short)100);
        ReflectionTestUtils.setField(preferred,"id",1L);ReflectionTestUtils.setField(preferred,"priority",(short)20);
        when(sources.findById(11L)).thenReturn(Optional.of(direct));when(sources.findById(1L)).thenReturn(Optional.of(preferred));
        var stored=new ArrayList<MarketPriceEntity>();
        when(prices.findBySecurityIdAndIntervalCodeAndPriceTimestampBetween(eq(security.getId()),eq("1d"),any(),any()))
                .thenAnswer(call->List.copyOf(stored));
        when(prices.saveAndFlush(any())).thenAnswer(call->{MarketPriceEntity e=call.getArgument(0);
            if(e.getId()==null){ReflectionTestUtils.setField(e,"id",(long)stored.size()+1);stored.add(e);}return e;});
        Map<UUID,RawPayloadEntity> payloads=new HashMap<>();
        when(raws.findById(any())).thenAnswer(call->Optional.ofNullable(payloads.get(call.getArgument(0))));
        var rawIds=new ArrayList<UUID>();
        for (int batch=0;batch<4;batch++) {
            var source=batch<2?direct:preferred;int close=batch==3?90:batch==2?110:100;
            Instant fetched=Instant.parse(batch==3?"2026-10-07T00:00:00Z":"2026-10-08T00:00:00Z");
            var run=IngestionRunEntity.start(source,null,"MANUAL",json.createObjectNode(),URI.create("internal://backfill-test"),json.createObjectNode(),fetched);
            ReflectionTestUtils.setField(run,"id",UUID.randomUUID());
            var version=DataVersionEntity.acceptedForRun("MARKET_PRICE",run.getId(),1,"hash");
            ReflectionTestUtils.setField(version,"id",UUID.randomUUID());
            var body=json.createObjectNode().put("symbol","FPT").put("provider",source.getProvider()).put("schema_version","market_price.v1").put("count",100);
            var data=body.putArray("data");
            for(int i=0;i<100;i++)data.addObject().put("symbol","FPT").put("date",LocalDate.of(2016,1,1).plusDays(i).toString())
                    .put("open_price",close).put("high_price",close).put("low_price",close).put("close_price",close);
            var raw=RawPayloadEntity.create(run,source,"FPT","OHLCV","FPT",null,"application/json",body,null,"hash",fetched,security.getId());
            ReflectionTestUtils.setField(raw,"id",UUID.randomUUID());payloads.put(raw.getId(),raw);rawIds.add(raw.getId());
            when(versions.findByIdForUpdate(version.getId())).thenReturn(Optional.of(version));
            when(raws.findByIngestionRunIdOrderByFetchedAtDesc(run.getId())).thenReturn(List.of(raw));
            assertThat(writes.build(version.getId(),run)).isEqualTo(100);
            assertThat(version.getStatus()).isEqualTo("ACTIVATED");
        }
        assertThat(stored).hasSize(200);
        assertThat(stored.stream().filter(p->Boolean.TRUE.equals(p.getIsCanonical())).toList()).hasSize(100)
                .allSatisfy(p->{assertThat(p.getDataSourceId()).isEqualTo(1L);assertThat(p.getClosePrice()).isEqualByComparingTo("110");assertThat(p.getRawPayloadId()).isEqualTo(rawIds.get(2));});
        verify(prices,never()).findBucketForUpdate(any(),any(),any());
        verify(prices,never()).findBySecurityIdAndPriceTimestampAndIntervalCodeAndDataSourceId(any(),any(),any(),any());
        verify(sources,times(4)).findById(11L);verify(sources,times(2)).findById(1L);
    }
}
