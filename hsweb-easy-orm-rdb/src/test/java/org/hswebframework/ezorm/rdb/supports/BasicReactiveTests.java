package org.hswebframework.ezorm.rdb.supports;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.SyncSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.reactive.ReactiveSyncSqlExecutor;
import org.hswebframework.ezorm.rdb.mapping.EntityColumnMapping;
import org.hswebframework.ezorm.rdb.mapping.MappingFeatureType;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.SyncRepository;
import org.hswebframework.ezorm.rdb.mapping.defaults.DefaultReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.defaults.DefaultSyncRepository;
import org.hswebframework.ezorm.rdb.mapping.jpa.JpaEntityTableMetadataParser;
import org.hswebframework.ezorm.rdb.mapping.wrapper.EntityResultWrapper;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.DatabaseOperator;
import org.hswebframework.ezorm.rdb.operator.DefaultDatabaseOperator;
import org.hswebframework.ezorm.rdb.operator.dml.insert.InsertOperator;
import org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers.map;
import static org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.*;

@Slf4j
public abstract class BasicReactiveTests {

    protected ReactiveRepository<BasicTestEntity, String> repository;

    protected abstract RDBSchemaMetadata getSchema();

    protected abstract Dialect getDialect();

    protected abstract ReactiveSqlExecutor getSqlExecutor();

    protected RDBDatabaseMetadata getDatabase() {
        RDBDatabaseMetadata metadata = new RDBDatabaseMetadata(getDialect());

        RDBSchemaMetadata schema = getSchema();
        log.debug(schema.toString());

        metadata.setCurrentSchema(schema);
        metadata.addSchema(schema);
        ReactiveSqlExecutor sqlExecutor = getSqlExecutor();

        metadata.addFeature(sqlExecutor);
        metadata.addFeature(ReactiveSyncSqlExecutor.of(sqlExecutor));

        return metadata;
    }

    @After
    public void after() {
        try {
            getSqlExecutor().execute(Mono.just(SqlRequests.of("drop table entity_test_table"))).block();
        } catch (Exception e) {

        }
    }

    @Before
    public void init() {
        RDBDatabaseMetadata metadata = getDatabase();
        DatabaseOperator operator = DefaultDatabaseOperator.of(metadata);

        JpaEntityTableMetadataParser parser = new JpaEntityTableMetadataParser();
        parser.setDatabaseMetadata(metadata);

        RDBTableMetadata table = parser.parseTable(BasicTestEntity.class).orElseThrow(NullPointerException::new);

        operator.ddl()
                .createOrAlter(table)
                .commit()
                .reactive()
                .block();
        EntityResultWrapper<BasicTestEntity> wrapper = new EntityResultWrapper<>(BasicTestEntity::new);
        wrapper.setMapping(table.<EntityColumnMapping>getFeature(MappingFeatureType.columnPropertyMapping.createFeatureId(BasicTestEntity.class)).orElseThrow(NullPointerException::new));


        repository = new DefaultReactiveRepository<>(operator, table, BasicTestEntity.class, wrapper);
    }

    @Test
    public void testReactivePager() {
        RDBDatabaseMetadata database = getDatabase();
        DatabaseOperator operator = DefaultDatabaseOperator.of(database);
        try {
            operator.ddl()
                    .createOrAlter("test_reactive_pager")
                    .addColumn().name("id").number(32).primaryKey().comment("ID").commit()
                    .commit()
                    .reactive()
                    .block();

            InsertOperator insert = operator.dml()
                    .insert("test_reactive_pager")
                    .columns("id");

            for (int i = 0; i < 100; i++) {
                insert.values(i + 1);
            }

            StepVerifier.create(insert.execute().reactive())
                    .expectNext(100)
                    .verifyComplete();

            for (int i = 0; i < 10; i++) {
                StepVerifier.create(operator.dml()
                        .query("test_reactive_pager")
                        .select("id")
                        .paging(i, 10)
                        .fetch(map())
                        .reactive()
                        .log(getClass().getName())
                        .map(map -> map.get("id"))
                        .map(Number.class::cast)
                        .collect(Collectors.summingInt(Number::intValue)))
                        .expectNext((((i * 10) + 1) + ((i + 1) * 10)) * 10 / 2)
                        .verifyComplete();
            }
        } finally {
            try {
                operator.sql().reactive()
                        .execute(Mono.just(SqlRequests.of("drop table " + database.getCurrentSchema().getName() + ".test_reactive_pager")))
                        .block();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    @Test
    public void testRepositoryInsertBach() {
        //10次insert
        StepVerifier.create(repository.insert(Flux.range(0, 10)
                .map(integer -> BasicTestEntity.builder()
                        .id("test_id_2_" + integer)
                        .balance(1000L)
                        .name("test2:" + integer)
                        .createTime(new Date())
                        .tags(Arrays.asList("a", "b", "c", "d"))
                        .state((byte) 1)
                        .stateEnum(StateEnum.enabled)
                        .build())))
                .expectNext(10)
                .verifyComplete();

        //每30条数据批量insert
        StepVerifier
                .create(repository.insertBatch(Flux.range(0, 100)
                        .map(integer -> BasicTestEntity.builder()
                                .id("test_id_" + integer)
                                .balance(1000L)
                                .name("test:" + integer)
                                .createTime(new Date())
                                .state((byte) 1)
                                .build())
                        .buffer(30)))
                .expectNext(100)
                .verifyComplete();

    }


    @Test
    public void testRepositoryCurd() {
        BasicTestEntity entity = BasicTestEntity.builder()
                .id("test_id")
                .balance(1000L)
                .name("test")
                .tags(Arrays.asList("a", "b", "c", "d"))
                .createTime(new Date())
                .state((byte) 1)
                .build();

        Mono.just(entity)
                .as(repository::insert)
                .as(StepVerifier::create)
                .expectNext(1)
                .verifyComplete();

        Mono.just(entity.getId())
                .as(repository::findById)
                .as(StepVerifier::create)
                .expectNext(entity)
                .verifyComplete();

        repository.createQuery()
                .count()
                .as(StepVerifier::create)
                .expectNext(1)
                .verifyComplete();

        repository.createQuery()
                .where(entity::getId)
                .orderBy(desc(entity::getId))
                .fetch()
                .as(StepVerifier::create)
                .expectNext(entity)
                .verifyComplete();

        entity.setBalance(100000L);
        repository.createUpdate()
                .set(entity::getBalance)
                .where(entity::getId)
                .execute()
                .as(StepVerifier::create)
                .expectNext(1)
                .verifyComplete();

        repository.createDelete()
                .where(entity::getId)
                .execute()
                .as(StepVerifier::create)
                .expectNext(1)
                .verifyComplete();

    }

}
