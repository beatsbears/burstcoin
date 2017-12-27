package brs.db.sql;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import brs.Burst;
import brs.db.EntityTable;
import brs.db.BurstIterator;
import brs.db.BurstKey;
import brs.db.sql.DbUtils;

import java.sql.ResultSet;
import java.sql.SQLException;

import static brs.schema.Tables.*;
import static org.jooq.impl.DSL.*;

import org.jooq.impl.DSL;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.ResultQuery;
import org.jooq.SelectJoinStep;
import org.jooq.impl.TableImpl;
import org.jooq.SelectQuery;
import org.jooq.UpdateQuery;
import org.jooq.Row1;
import org.jooq.exception.DataAccessException;

public abstract class EntitySqlTable<T> extends DerivedSqlTable implements EntityTable<T> {
  protected final DbKey.Factory<T> dbKeyFactory;
  private final boolean multiversion;
  private final String defaultSort;

  //    private final Timer getByKeyTimer;
  //    private final Timer getByKeyAndHeightTimer;
  //    private final Timer getByClauseTimer;
  //    private final Timer getByClauseAndHeightTimer;
  //    private final Timer insertTimer;
  protected EntitySqlTable(String table, TableImpl<?> tableClass, BurstKey.Factory<T> dbKeyFactory) {
    this(table, tableClass, dbKeyFactory, false);
  }

  EntitySqlTable(String table, TableImpl<?> tableClass, BurstKey.Factory<T> dbKeyFactory, boolean multiversion) {
    super(table, tableClass);
    this.dbKeyFactory = (DbKey.Factory<T>) dbKeyFactory;
    this.multiversion = multiversion;
    this.defaultSort = " ORDER BY " + (multiversion ? this.dbKeyFactory.getPKColumns() : " height DESC ");

    //        getByKeyTimer =  Burst.metrics.timer(MetricRegistry.name(DerivedSqlTable.class, table,"getByKey"));
    //        getByKeyAndHeightTimer =  Burst.metrics.timer(MetricRegistry.name(DerivedSqlTable.class, table,"getByKeyAndHeight"));
    //        getByClauseTimer =  Burst.metrics.timer(MetricRegistry.name(DerivedSqlTable.class, table,"getByClause"));
    //        getByClauseAndHeightTimer =  Burst.metrics.timer(MetricRegistry.name(DerivedSqlTable.class, table,"getByClauseAndHeight"));
    //        insertTimer =  Burst.metrics.timer(MetricRegistry.name(DerivedSqlTable.class, table,"insert"));
  }

  protected abstract T load(DSLContext ctx, ResultSet rs) throws SQLException;

  protected void save(DSLContext ctx, T t) throws SQLException {
  }

  protected String defaultSort() {
    return defaultSort;
  }

  @Override
  public final void checkAvailable(int height) {
    if (multiversion && height < Burst.getBlockchainProcessor().getMinRollbackHeight()) {
      throw new IllegalArgumentException("Historical data as of height " + height + " not available, set brs.trimDerivedTables=false and re-scan");
    }
  }

  @Override
  public T get(BurstKey nxtKey) {
    DbKey dbKey = (DbKey) nxtKey;
    if (Db.isInTransaction()) {
      T t = (T) Db.getCache(table).get(dbKey);
      if (t != null) {
        return t;
      }
    }
    try (DSLContext ctx = Db.getDSLContext()) {
      SelectQuery query = ctx.selectQuery();
      query.addFrom(tableClass);
      query.addConditions(dbKey.getPKConditions(tableClass));
      if ( multiversion ) {
        query.addConditions(tableClass.field("latest", Boolean.class).isTrue());
      }
      query.addLimit(1);

      return get(ctx, query, true);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public T get(BurstKey nxtKey, int height) {
    DbKey dbKey = (DbKey) nxtKey;
    checkAvailable(height);

    try (DSLContext ctx = Db.getDSLContext()) {
      SelectQuery query = ctx.selectQuery();
      query.addFrom(tableClass);
      query.addConditions(dbKey.getPKConditions(tableClass));
      query.addConditions(tableClass.field("height", Integer.class).le(height));
      if ( multiversion ) {
        Table       innerTable = tableClass.as("b");
        SelectQuery innerQuery = ctx.selectQuery();
        innerQuery.addConditions(innerTable.field("height", Integer.class).gt(height));
        innerQuery.addConditions(dbKey.getPKConditions(innerTable));
        // ToDo: verify:
        // (latest = TRUE OR EXISTS ( SELECT 1 FROM " + table + dbKeyFactory.getPKClause() + " AND height > ?))"
        query.addConditions(
          tableClass.field("latest", Boolean.class).isTrue().or(
            DSL.field(DSL.exists(innerQuery))
          )
        );
      }
      query.addOrderBy(tableClass.field("height").desc());
      query.addLimit(1);

      return get(ctx, query, false);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public T getBy(Condition condition) {
    try (DSLContext ctx = Db.getDSLContext()) {
      SelectQuery query = ctx.selectQuery();
      query.addFrom(tableClass);
      query.addConditions(condition);
      if ( multiversion ) {
        query.addConditions(tableClass.field("latest", Boolean.class).isTrue());
      }
      query.addLimit(1);

      return get(ctx, query, true);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public T getBy(Condition condition, int height) {
    checkAvailable(height);

    try (DSLContext ctx = Db.getDSLContext()) {
      SelectQuery query = ctx.selectQuery();
      query.addFrom(tableClass);
      query.addConditions(condition);
      query.addConditions(tableClass.field("height", Integer.class).le(height));
      if ( multiversion ) {
        Table       innerTable = tableClass.as("b");
        SelectQuery innerQuery = ctx.selectQuery();
        innerQuery.addConditions(innerTable.field("height", Integer.class).gt(height));
        dbKeyFactory.applySelfJoin(innerQuery, innerTable, tableClass);
        query.addConditions(
          tableClass.field("latest", Boolean.class).isTrue().or(
            DSL.field(DSL.exists(innerQuery))
          )
        );
      }
      query.addOrderBy(tableClass.field("height").desc());
      query.addLimit(1);

      return get(ctx, query, false);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  private T get(DSLContext ctx, SelectQuery query, boolean cache) throws SQLException {
    final boolean doCache = cache && Db.isInTransaction();
    ResultSet rs = query.fetchResultSet();

    if (!rs.next()) {
      return null;
    }
    T t = null;
    DbKey dbKey = null;
    if (doCache) {
      dbKey = (DbKey) dbKeyFactory.newKey(rs);
      t = (T) Db.getCache(table).get(dbKey);
    }
    if (t == null) {
      t = load(ctx, rs);
      if (doCache) {
        Db.getCache(table).put(dbKey, t);
      }
    }
    if (rs.next()) {
      throw new RuntimeException("Multiple records found");
    }
    return t;
  }

  @Override
  public BurstIterator<T> getManyBy(Condition condition, int from, int to) {
    return getManyBy(condition, from, to, defaultSort());
  }

  @Override
  public BurstIterator<T> getManyBy(Condition condition, int from, int to, String sort) {
    try (DSLContext ctx = Db.getDSLContext()) {
      SelectQuery query = ctx.selectQuery();
      query.addFrom(tableClass);
      query.addConditions(condition);
      if ( multiversion ) {
        query.addConditions(tableClass.field("latest", Boolean.class).isTrue());
      }
      DbUtils.applyLimits(query, from, to);
      return getManyBy(ctx, query, true);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public BurstIterator<T> getManyBy(Condition condition, int height, int from, int to) {
    return getManyBy(condition, height, from, to, defaultSort());
  }

  @Override
  public BurstIterator<T> getManyBy(Condition condition, int height, int from, int to, String sort) {
    checkAvailable(height);
    try (DSLContext ctx = Db.getDSLContext()) {
      SelectQuery query = ctx.selectQuery();
      query.addFrom(tableClass);
      query.addConditions(condition);
      query.addConditions(tableClass.field("height", Integer.class).le(height));
      if ( multiversion ) {
        Table       innerTableB = tableClass.as("b");
        SelectQuery innerQueryB = ctx.selectQuery();
        innerQueryB.addConditions(innerTableB.field("height", Integer.class).gt(height));
        dbKeyFactory.applySelfJoin(innerQueryB, innerTableB, tableClass);

        Table       innerTableC = tableClass.as("c");
        SelectQuery innerQueryC = ctx.selectQuery();
        innerQueryC.addConditions(
          innerTableC.field("height", Integer.class).le(height).and(
            innerTableC.field("height").gt(tableClass.field("height"))
          )
        );
        dbKeyFactory.applySelfJoin(innerQueryC, innerTableC, tableClass);

        query.addConditions(
          tableClass.field("latest", Boolean.class).isTrue().or(
            DSL.field(
              DSL.exists(innerQueryB).and(DSL.notExists(innerQueryC))
            )
          )
        );
      }
      // ToDo: addSort
      DbUtils.applyLimits(query, from, to);
      return getManyBy(ctx, query, true);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  public BurstIterator<T> getManyBy(DSLContext ctx, SelectQuery query, boolean cache) {
    final boolean doCache = cache && Db.isInTransaction();
    return new DbIterator<>(ctx, query.fetchResultSet(), new DbIterator.ResultSetReader<T>() {
        @Override
        public T get(DSLContext ctx, ResultSet rs) throws Exception {
          T t = null;
          DbKey dbKey = null;
          if (doCache) {
            dbKey = (DbKey) dbKeyFactory.newKey(rs);
            t = (T) Db.getCache(table).get(dbKey);
          }
          if (t == null) {
            t = load(ctx, rs);
            if (doCache) {
              Db.getCache(table).put(dbKey, t);
            }
          }
          return t;
        }
      });
  }

  @Override
  public BurstIterator<T> getAll(int from, int to) {
    return getAll(from, to, defaultSort());
  }

  @Override
  public BurstIterator<T> getAll(int from, int to, String sort) {
    try (DSLContext ctx = Db.getDSLContext()) {
      SelectQuery query = ctx.selectQuery();
      query.addFrom(tableClass);
      if ( multiversion ) {
        query.addConditions(tableClass.field("latest", Boolean.class).isTrue());
      }
      // ToDo: sort
      DbUtils.applyLimits(query, from, to);
      return getManyBy(ctx, query, true);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public BurstIterator<T> getAll(int height, int from, int to) {
    return getAll(height, from, to, defaultSort());
  }

  @Override
  public BurstIterator<T> getAll(int height, int from, int to, String sort) {
    checkAvailable(height);
    try (DSLContext ctx = Db.getDSLContext()) {
      SelectQuery query = ctx.selectQuery();
      query.addFrom(tableClass);
      query.addConditions(tableClass.field("height", Integer.class).le(height));
      if ( multiversion ) {
        Table       innerTableB = tableClass.as("b");
        SelectQuery innerQueryB = ctx.selectQuery();
        innerQueryB.addConditions(innerTableB.field("height", Integer.class).gt(height));
        dbKeyFactory.applySelfJoin(innerQueryB, innerTableB, tableClass);

        Table       innerTableC = tableClass.as("c");
        SelectQuery innerQueryC = ctx.selectQuery();
        innerQueryC.addConditions(
          innerTableC.field("height", Integer.class).le(height).and(
            innerTableC.field("height").gt(tableClass.field("height"))
          )
        );
        dbKeyFactory.applySelfJoin(innerQueryC, innerTableC, tableClass);

        query.addConditions(
          tableClass.field("latest", Boolean.class).isTrue().or(
            DSL.field(
              DSL.exists(innerQueryB).and(DSL.notExists(innerQueryC))
            )
          )
        );
      }
      // ToDo: addSort
      query.addLimit(from, to);
      return getManyBy(ctx, query, true);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public int getCount() {
    try ( DSLContext ctx = Db.getDSLContext() ) {
      TableImpl<?>      t = tableClass;
      SelectJoinStep<?> r = ctx.selectCount().from(t);
      return ( multiversion ? r.where(t.field("latest").isTrue()) : r ).fetchOne(0, int.class);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public int getRowCount() {
    try ( DSLContext ctx = Db.getDSLContext() ) {
      return ctx.selectCount().from(tableClass).fetchOne(0, int.class);
    }
    catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public void insert(T t) {
    if (!Db.isInTransaction()) {
      throw new IllegalStateException("Not in transaction");
    }
    DbKey dbKey = (DbKey) dbKeyFactory.newKey(t);
    T cachedT = (T) Db.getCache(table).get(dbKey);
    if (cachedT == null) {
      Db.getCache(table).put(dbKey, t);
    } else if (t != cachedT) { // not a bug
      throw new IllegalStateException("Different instance found in Db cache, perhaps trying to save an object "
                                      + "that was read outside the current transaction");
    }
    try ( DSLContext ctx = Db.getDSLContext() ) {
      if (multiversion) {
        UpdateQuery query = ctx.updateQuery(tableClass);
        query.addValue(
          tableClass.field("latest", Boolean.class),
          false
        );
        query.addConditions(dbKey.getPKConditions(tableClass));
        query.addConditions(tableClass.field("latest", Boolean.class).isTrue());
        query.execute();
      }
      save(ctx, t);
    } catch (SQLException e) {
      throw new RuntimeException(e.toString(), e);
    }
  }

  @Override
  public void rollback(int height) {
    super.rollback(height);
    Db.getCache(table).clear();
  }

  @Override
  public void truncate() {
    super.truncate();
    Db.getCache(table).clear();
  }

}
