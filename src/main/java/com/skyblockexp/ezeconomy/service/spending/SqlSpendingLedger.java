package com.skyblockexp.ezeconomy.service.spending;

import com.skyblockexp.ezeconomy.api.storage.*;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.zaxxer.hikari.HikariDataSource;
import com.skyblockexp.ezeconomy.storage.jaloquent.model.BalanceModel;
import com.skyblockexp.ezeconomy.storage.mysql.MySQLBalanceBackgroundPersistenceService;
import com.skyblockexp.ezeconomy.storage.sqlite.SQLiteBackgroundPersistenceService;
import java.math.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Successful withdrawals and opaque accounting attribution share the balance SQL transaction.
 * No Bukkit event, Player object, scheduler or memory-only queue participates in settlement.
 * API calls performing SQL must be made by an existing bounded economy IO worker.
 */
public final class SqlSpendingLedger {
    public record Row(UUID subject,UUID payer,BigDecimal amount){}
    public record Snapshot(long since,List<Row> rows){public Snapshot{rows=List.copyOf(rows);}}
    private record Binding(Object owner,Function<UUID,UUID> resolver,String currency){}
    private record Backend(EzEconomyPlugin plugin,HikariDataSource pool,Connection connection,Object lock,String balance,boolean mysql){}
    private static volatile Backend backend;
    private static final java.util.concurrent.atomic.AtomicReference<Binding> BINDING=new java.util.concurrent.atomic.AtomicReference<>();
    private static volatile boolean prepared;
    private static final ThreadLocal<Integer> EXCLUDED=ThreadLocal.withInitial(()->0);
    private static final ThreadLocal<String> ORDER=new ThreadLocal<>();
    @FunctionalInterface public interface Scope extends AutoCloseable {void close();}
    public static Scope order(String id){if(id==null||!id.matches("[A-Za-z0-9_:/.-]{1,96}"))throw new IllegalArgumentException("付款引用无效");String previous=ORDER.get();ORDER.set(id);return ()->{if(previous==null)ORDER.remove();else ORDER.set(previous);};}
    private SqlSpendingLedger(){}
    /** Used by source-level storage integration; a shared JDBC connection cannot run independent transactions. */
    public static Object mysqlLock(Object legacy,Connection connection,HikariDataSource pool){return pool==null&&connection!=null?connection:legacy;}
    /** Legacy shared-connection transfer also needs one atomic transaction; it never earns ranking credit.
     * Event dispatch happens outside the connection monitor, so listeners cannot deadlock database IO.
     */
    public static com.skyblockexp.ezeconomy.storage.TransferResult mysqlTransfer(EzEconomyPlugin p,HikariDataSource pool,Connection c,String table,UUID from,UUID to,String currency,double debit,double credit){
        if(pool!=null)return null;
        double beforeFrom=0,beforeTo=0;
        try{
            if(!Double.isFinite(debit)||!Double.isFinite(credit)||debit<0||credit<0||c==null)throw new SQLException("转账金额或连接无效");
            synchronized(c){beforeFrom=balance(c,table,from,currency);beforeTo=balance(c,table,to,currency);}
            if(p!=null&&!com.skyblockexp.ezeconomy.util.EventDispatcher.fireSyncAndAllow(p,new com.skyblockexp.ezeconomy.api.events.PreTransactionEvent(from,to,BigDecimal.valueOf(debit),com.skyblockexp.ezeconomy.api.events.TransactionType.TRANSFER)))return com.skyblockexp.ezeconomy.storage.TransferResult.failure(beforeFrom,beforeTo);
            com.skyblockexp.ezeconomy.storage.TransferResult result;
            synchronized(c){result=transfer(c,table,from,to,currency,debit,credit);}
            try{var cache=com.skyblockexp.ezeconomy.cache.CacheManager.getProvider();cache.remove("bal:"+from+":"+currency);cache.remove("bal:"+to+":"+currency);}catch(RuntimeException ignored){}
            if(p!=null)try{com.skyblockexp.ezeconomy.util.EventDispatcher.fireSync(p,new com.skyblockexp.ezeconomy.api.events.PostTransactionEvent(from,to,BigDecimal.valueOf(debit),com.skyblockexp.ezeconomy.api.events.TransactionType.TRANSFER,result.isSuccess(),BigDecimal.valueOf(beforeFrom),BigDecimal.valueOf(result.getFromBalance()),BigDecimal.valueOf(beforeTo),BigDecimal.valueOf(result.getToBalance())));}catch(RuntimeException postFailure){p.getLogger().warning("转账已结算，后置通知失败："+postFailure.getMessage());}
            return result;
        }catch(Exception error){if(p!=null)p.getLogger().severe("共享连接转账未确认，请核实余额："+error.getMessage());return com.skyblockexp.ezeconomy.storage.TransferResult.failure(beforeFrom,beforeTo);}
    }
    private static com.skyblockexp.ezeconomy.storage.TransferResult transfer(Connection c,String table,UUID from,UUID to,String currency,double debit,double credit)throws SQLException{
        safeTable(table);if(!c.getAutoCommit())throw new SQLException("禁止嵌套转账事务");c.setAutoCommit(false);boolean committed=false;
        try{
            double source=balance(c,table,from,currency),target=balance(c,table,to,currency);
            if(source<debit){c.rollback();return com.skyblockexp.ezeconomy.storage.TransferResult.failure(source,target);}
            if(debit>0)try(var s=c.prepareStatement("UPDATE "+table+" SET balance=balance-? WHERE id=? AND balance>=?")){s.setDouble(1,debit);s.setString(2,BalanceModel.idFor(from,currency));s.setDouble(3,debit);if(s.executeUpdate()!=1){c.rollback();return com.skyblockexp.ezeconomy.storage.TransferResult.failure(source,target);}}
            if(credit>0)try(var s=c.prepareStatement("INSERT INTO "+table+"(id,uuid,currency,balance) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE balance=balance+VALUES(balance)")){s.setString(1,BalanceModel.idFor(to,currency));s.setString(2,to.toString());s.setString(3,currency);s.setDouble(4,credit);s.executeUpdate();}
            var result=com.skyblockexp.ezeconomy.storage.TransferResult.success(balance(c,table,from,currency),balance(c,table,to,currency));c.commit();committed=true;return result;
        }finally{if(!committed)try{c.rollback();}catch(SQLException ignored){}try{c.setAutoCommit(true);}catch(SQLException error){if(!committed)throw error;}}
    }
    public static synchronized void mysql(EzEconomyPlugin p,HikariDataSource pool,Connection c,String table){
        backend=new Backend(p,pool,c,c==null?new Object():c,table,true);BINDING.set(null);prepared=false;
    }
    public static synchronized void sqlite(EzEconomyPlugin p,Connection c,Object lock,String table){
        backend=new Backend(p,null,c,lock,table,false);BINDING.set(null);prepared=false;
    }
    public static synchronized void reconnect(EzEconomyPlugin plugin, Connection connection, String table) {
        Backend current = backend;
        if (current != null && current.mysql() && current.plugin() == plugin && current.balance().equals(table)) {
            backend = new Backend(plugin, current.pool(), connection, connection, table, true);
        }
    }
    public static void close(){BINDING.set(null);backend=null;prepared=false;EXCLUDED.remove();ORDER.remove();}
    public static boolean ready(){return prepared&&BINDING.get()!=null;}
    /** Call on IO, once after the subject index is ready. Unsupported backends fail explicitly. */
    public static AutoCloseable attach(Object owner,Function<UUID,UUID> resolver,String currency)throws SQLException{
        Backend b=Objects.requireNonNull(backend,"消费排行需要配套EzEconomy的MySQL或SQLite后端");
        if(currency==null||!currency.matches("[a-zA-Z0-9_-]{1,32}"))throw new IllegalArgumentException("消费货币ID无效");
        if(BINDING.get()!=null&&BINDING.get().owner()!=owner)throw new IllegalStateException("已有消费归属提供者");
        if(b.pool()!=null)throw new SQLException("消费排行需关闭performance.mysql.pool.enabled并重启");
        validateSettlement(b.plugin());
        sql(b,c->{schema(c,b.mysql());return null;});prepared=true;
        Binding next=new Binding(owner,Objects.requireNonNull(resolver),currency);if(backend!=b)throw new SQLException("经济后端已关闭");if(!BINDING.compareAndSet(null,next))throw new SQLException("已有消费归属提供者");
        return ()->BINDING.compareAndSet(next,null);
    }
    /** Validation belongs to the economy provider, never to a plugin consuming its public API. */
    public static void validateSettlement(EzEconomyPlugin plugin)throws SQLException{
        Backend b=backend;
        if(b==null||b.plugin()!=plugin)throw new SQLException("经济数据库未就绪");
        if(b.pool()!=null)throw new SQLException("消费记账需关闭performance.mysql.pool.enabled并重启");
        var cfg=plugin.getConfig();
        if(cfg.getBoolean("performance.fast-vault-mutations.enabled",true)||cfg.getBoolean("performance.balance-cache.enabled",true))throw new SQLException("消费排行需关闭fast-vault-mutations和balance-cache并重启");
        // Loaded provider state is checked as well; editing YAML alone cannot change an existing IO queue.
        try{
            Object provider=b.plugin().getStorage();
            for(String key: b.mysql()?List.of("balanceBackgroundPersistence","hotPathDataSource"):List.of("backgroundPersistence")){
                var field=provider.getClass().getDeclaredField(key);field.setAccessible(true);if(field.get(provider)!=null)throw new SQLException("经济后台余额队列仍在运行，请按同步余额配置重启");
            }
            var field=provider.getClass().getDeclaredField("balanceCacheEnabled");field.setAccessible(true);if(!Boolean.FALSE.equals(field.get(provider)))throw new SQLException("余额缓存尚未停用，请完整重启");
        }catch(ReflectiveOperationException error){throw new SQLException("经济存储布局尚未核验",error);}
    }
    private static String safeTable(String raw){if(raw==null||!raw.matches("[A-Za-z0-9_]{1,64}"))throw new IllegalArgumentException("余额表名无效");return raw;}

    /** The caller owns legacy schema names; the economy has no knowledge of their domain. */
    public static void importLegacy(String tablePrefix,String subjectColumn)throws SQLException{
        safeTable(tablePrefix);safeTable(subjectColumn);
        if(tablePrefix.length()>48||tablePrefix.equalsIgnoreCase("ezeconomy_spend"))throw new IllegalArgumentException("旧账本表前缀无效");
        Backend b=backend;if(b==null)throw new SQLException("消费记账后端未就绪");
        sql(b,c->{
            if(!c.getAutoCommit())throw new SQLException("禁止嵌套账本迁移事务");
            schema(c,b.mysql());
            String source=tablePrefix+"/"+subjectColumn;
            try(var s=c.prepareStatement("SELECT source FROM ezeconomy_spend_imports WHERE source=?")){
                s.setString(1,source);try(var r=s.executeQuery()){if(r.next())return null;}
            }
            boolean exists=false;
            try(var tables=c.getMetaData().getTables(c.getCatalog(),null,null,new String[]{"TABLE"})){
                while(tables.next())if(tablePrefix.equalsIgnoreCase(tables.getString("TABLE_NAME"))){exists=true;break;}
            }
            if(!exists)return null;
            if(BINDING.get()!=null)throw new SQLException("记账已绑定，不能导入旧账本");
            if(!c.getAutoCommit())throw new SQLException("禁止嵌套账本迁移事务");
            c.setAutoCommit(false);boolean committed=false;
            try{
                for(String table:List.of("ezeconomy_spend","ezeconomy_spend_orders","ezeconomy_spend_refunds")){
                    try(var s=c.createStatement();var r=s.executeQuery("SELECT 1 FROM "+table+" LIMIT 1")){
                        if(r.next())throw new SQLException("新账本已有记录，拒绝混合导入；请先核对旧账本");
                    }
                }
                try(var s=c.createStatement()){
                    s.executeUpdate("INSERT INTO ezeconomy_spend(subject,payer,currency,amount) SELECT "+subjectColumn+",payer,currency,amount FROM "+tablePrefix);
                    s.executeUpdate("INSERT INTO ezeconomy_spend_orders(id,subject,payer,currency,paid,refunded) SELECT id,"+subjectColumn+",payer,currency,paid,refunded FROM "+tablePrefix+"_orders");
                    s.executeUpdate("INSERT INTO ezeconomy_spend_refunds(id,payer,currency,amount,fingerprint) SELECT id,payer,currency,amount,fingerprint FROM "+tablePrefix+"_refunds");
                    s.executeUpdate("UPDATE ezeconomy_spend_meta SET started=(SELECT started FROM "+tablePrefix+"_meta WHERE id=1) WHERE id=1");
                }
                try(var s=c.prepareStatement("INSERT INTO ezeconomy_spend_imports(source) VALUES(?)")){s.setString(1,source);s.executeUpdate();}
                c.commit();committed=true;
            }finally{
                if(!committed)try{c.rollback();}catch(SQLException ignored){}
                try{c.setAutoCommit(true);}catch(SQLException error){if(!committed)throw error;}
            }
            return null;
        });
    }

    /** Settlement with an explicit caller-supplied subject does not depend on a ranking reader. */
    public static EconomyMutationResult withdrawForSubject(UUID subject,UUID payer,String currency,double amount)throws SQLException{
        Objects.requireNonNull(subject);Objects.requireNonNull(payer);
        Backend b=backend;if(b==null)throw new SQLException("消费记账后端未就绪");
        return sql(b,c->{
            if(!c.getAutoCommit())throw new SQLException("禁止嵌套余额事务");
            schema(c,b.mysql());
            return withdraw(c,b.balance(),b.mysql(),payer,subject,currency,amount);
        });
    }
    @FunctionalInterface private interface Sql<T>{T run(Connection c)throws SQLException;}
    private static <T>T sql(Backend b,Sql<T> work)throws SQLException{
        if(b.pool()!=null){try(Connection c=b.pool().getConnection()){return work.run(c);}}
        synchronized(b.lock()){if(b.connection()==null||b.connection().isClosed())throw new SQLException("经济数据库未连接");return work.run(b.connection());}
    }
    private static void schema(Connection c,boolean mysql)throws SQLException{
        try(Statement s=c.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ezeconomy_spend_imports (source VARCHAR(128) PRIMARY KEY)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ezeconomy_spend (subject VARCHAR(36) NOT NULL,payer VARCHAR(36) NOT NULL,currency VARCHAR(32) NOT NULL,amount "+(mysql?"DECIMAL(38,8)":"TEXT")+" NOT NULL,PRIMARY KEY(subject,payer,currency))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ezeconomy_spend_meta (id INTEGER PRIMARY KEY,started BIGINT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ezeconomy_spend_orders (id VARCHAR(96) PRIMARY KEY,subject VARCHAR(36) NOT NULL,payer VARCHAR(36) NOT NULL,currency VARCHAR(32) NOT NULL,paid VARCHAR(64) NOT NULL,refunded VARCHAR(64) NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS ezeconomy_spend_refunds (id VARCHAR(96) PRIMARY KEY,payer VARCHAR(36) NOT NULL,currency VARCHAR(32) NOT NULL,amount VARCHAR(64) NOT NULL,fingerprint VARCHAR(64) NOT NULL)");
        }
        try(PreparedStatement s=c.prepareStatement((mysql?"INSERT IGNORE":"INSERT OR IGNORE")+" INTO ezeconomy_spend_meta(id,started) VALUES(1,?)")){s.setLong(1,System.currentTimeMillis());s.executeUpdate();}
    }
    public static Snapshot snapshot(int maximum)throws SQLException{
        Backend b=backend;if(!ready()||b==null)throw new SQLException("消费统计尚未就绪");
        if(maximum<1||maximum>1_000_000)throw new IllegalArgumentException("统计记录上限无效");Binding settings=BINDING.get();if(settings==null)throw new SQLException("排行已关闭");
        return sql(b,c->{
            long since;try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT started FROM ezeconomy_spend_meta WHERE id=1")){if(!r.next())throw new SQLException("缺少消费统计起始时间");since=r.getLong(1);}
            List<Row> rows=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("SELECT subject,payer,amount FROM ezeconomy_spend WHERE currency=? ORDER BY subject,payer LIMIT ?")){
                s.setString(1,settings.currency());s.setInt(2,maximum+1);
                try(ResultSet r=s.executeQuery()){while(r.next()){if(rows.size()==maximum)throw new SQLException("消费明细超过配置上限，旧快照保留；请调高maximum-records");rows.add(new Row(UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),new BigDecimal(r.getString(3))));}}
            }return new Snapshot(since,rows);
        });
    }
    private static UUID subject(UUID payer,String currency){
        Binding b=BINDING.get();if(!prepared||b==null||EXCLUDED.get()>0||!b.currency().equals(currency))return null;
        return b.resolver().apply(payer);
    }
    public static EconomyMutationResult mysqlWithdraw(EzEconomyPlugin p,HikariDataSource pool,Connection c,String table,Object keyLock,MySQLBalanceBackgroundPersistenceService pending,UUID payer,String currency,double amount){
        UUID subject=subject(payer,currency);if(subject==null)return null;
        synchronized(pool==null?Objects.requireNonNull(c):keyLock){
            try{if(pending!=null)pending.flushIdSync(BalanceModel.idFor(payer,currency));
                return sql(new Backend(p,pool,c,c,table,true),connection->withdraw(connection,table,true,payer,subject,currency,amount));
            }catch(Exception e){p.getLogger().severe("[消费排行] 扣款事务未确认，请核对订单与余额："+e.getMessage());return EconomyMutationResult.failure(0,"Storage failure / review required");}
        }
    }
    public static EconomyMutationResult sqliteWithdraw(EzEconomyPlugin p,Connection c,Object lock,String table,SQLiteBackgroundPersistenceService pending,UUID payer,String currency,double amount){
        UUID subject=subject(payer,currency);if(subject==null)return null;
        synchronized(lock){try{if(pending!=null)pending.flushIdSync(BalanceModel.idFor(payer,currency));
            return withdraw(c,table,false,payer,subject,currency,amount);
        }catch(Exception e){p.getLogger().severe("[消费排行] 扣款事务未确认，请核对订单与余额："+e.getMessage());return EconomyMutationResult.failure(0,"Storage failure / review required");}}
    }
    static EconomyMutationResult withdraw(Connection c,String table,boolean mysql,UUID payer,UUID subject,String currency,double value)throws SQLException{
        safeTable(table);if(!Double.isFinite(value)||value<=0)return EconomyMutationResult.failure(0,"Invalid amount");
        BigDecimal amount=BigDecimal.valueOf(value).setScale(8,RoundingMode.UNNECESSARY);
        if(!c.getAutoCommit())throw new SQLException("禁止嵌套余额事务");
        c.setAutoCommit(false);boolean committed=false;
        try{
            String id=ORDER.get();if(id==null)id="auto/"+UUID.randomUUID();
            try(PreparedStatement s=c.prepareStatement("SELECT payer,currency,paid FROM ezeconomy_spend_orders WHERE id=?")){
                s.setString(1,id);try(ResultSet r=s.executeQuery()){if(r.next()){
                    if(!payer.toString().equals(r.getString(1))||!currency.equals(r.getString(2))||amount.compareTo(new BigDecimal(r.getString(3)))!=0)throw new SQLException("重复付款引用内容不同");
                    double current=balance(c,table,payer,currency);c.rollback();return EconomyMutationResult.success(current);
                }}
            }
            int changed;try(PreparedStatement s=c.prepareStatement("UPDATE "+table+" SET balance=balance-? WHERE id=? AND balance>=?")){
                s.setDouble(1,value);s.setString(2,BalanceModel.idFor(payer,currency));s.setDouble(3,value);changed=s.executeUpdate();}
            double balance;try(PreparedStatement s=c.prepareStatement("SELECT balance FROM "+table+" WHERE id=?")){
                s.setString(1,BalanceModel.idFor(payer,currency));try(ResultSet r=s.executeQuery()){balance=r.next()?r.getDouble(1):0;}}
            if(changed==0){c.rollback();return EconomyMutationResult.failure(balance,"Insufficient funds");}
            try(PreparedStatement s=c.prepareStatement("INSERT INTO ezeconomy_spend_orders(id,subject,payer,currency,paid,refunded) VALUES(?,?,?,?,?,?)")){
                s.setString(1,id);s.setString(2,subject.toString());s.setString(3,payer.toString());s.setString(4,currency);s.setString(5,amount.toPlainString());s.setString(6,"0");s.executeUpdate();}
            if(mysql){try(PreparedStatement s=c.prepareStatement("INSERT INTO ezeconomy_spend(subject,payer,currency,amount) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE amount=amount+VALUES(amount)")){
                s.setString(1,subject.toString());s.setString(2,payer.toString());s.setString(3,currency);s.setBigDecimal(4,amount);s.executeUpdate();}
            }else{
                BigDecimal previous=BigDecimal.ZERO;try(PreparedStatement s=c.prepareStatement("SELECT amount FROM ezeconomy_spend WHERE subject=? AND payer=? AND currency=?")){
                    s.setString(1,subject.toString());s.setString(2,payer.toString());s.setString(3,currency);try(ResultSet r=s.executeQuery()){if(r.next())previous=new BigDecimal(r.getString(1));}}
                try(PreparedStatement s=c.prepareStatement("INSERT INTO ezeconomy_spend(subject,payer,currency,amount) VALUES(?,?,?,?) ON CONFLICT(subject,payer,currency) DO UPDATE SET amount=excluded.amount")){
                    s.setString(1,subject.toString());s.setString(2,payer.toString());s.setString(3,currency);s.setString(4,previous.add(amount).toPlainString());s.executeUpdate();}
            }
            c.commit();committed=true;return EconomyMutationResult.success(balance);
        }finally{
            if(!committed)try{c.rollback();}catch(SQLException ignored){}
            // A post-commit cleanup failure must never turn a confirmed debit into a second charge.
            try{c.setAutoCommit(true);}catch(SQLException error){if(!committed)throw error;}
        }
    }
    private static double balance(Connection c,String table,UUID payer,String currency)throws SQLException{
        try(PreparedStatement s=c.prepareStatement("SELECT balance FROM "+safeTable(table)+" WHERE id=?")){s.setString(1,BalanceModel.idFor(payer,currency));try(ResultSet r=s.executeQuery()){return r.next()?r.getDouble(1):0;}}
    }
    private record Paid(String id,UUID subject,UUID payer,BigDecimal paid,BigDecimal refunded){}
    /** Explicit linked refund. Rewards, sale proceeds and transfers never call this method.
     * originalTotal includes old receipts not present in this new statistics ledger.
     */
    public static EconomyMutationResult refund(String refundId,List<String> sources,BigDecimal originalTotal,UUID recipient,String currency,double value)throws SQLException{
        return refundInternal(refundId,sources,originalTotal,recipient,currency,value,true);
    }
    /** Administrator has independently verified a refund already paid; never credits money again. */
    public static EconomyMutationResult acknowledgeRefund(String refundId,List<String> sources,BigDecimal originalTotal,UUID recipient,String currency,double value)throws SQLException{
        return refundInternal(refundId,sources,originalTotal,recipient,currency,value,false);
    }
    private static EconomyMutationResult refundInternal(String refundId,List<String> sources,BigDecimal originalTotal,UUID recipient,String currency,double value,boolean credit)throws SQLException{
        Backend b=backend;if(b==null||b.pool()!=null)throw new SQLException("消费退款需要同步经济后端");
        if(refundId==null||!refundId.matches("[A-Za-z0-9_:/.-]{1,96}")||sources.size()>1000||new HashSet<>(sources).size()!=sources.size())throw new IllegalArgumentException("退款引用无效");
        if(!Double.isFinite(value)||value<=0||originalTotal.signum()<=0)throw new IllegalArgumentException("退款金额无效");
        BigDecimal amount=BigDecimal.valueOf(value).setScale(8,RoundingMode.UNNECESSARY);
        if(amount.compareTo(originalTotal)>0)throw new IllegalArgumentException("退款超过原价");
        String fingerprint;
        try{fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((credit+"|"+originalTotal.stripTrailingZeros().toPlainString()+"|"+String.join(",",sources.stream().sorted().toList())).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return sql(b,c->{
            if(!prepared){schema(c,b.mysql());prepared=true;}
            if(!c.getAutoCommit())throw new SQLException("禁止嵌套退款事务");c.setAutoCommit(false);boolean committed=false;
            try{
                try(PreparedStatement s=c.prepareStatement("SELECT payer,currency,amount,fingerprint FROM ezeconomy_spend_refunds WHERE id=?")){
                    s.setString(1,refundId);try(ResultSet r=s.executeQuery()){if(r.next()){
                        if(!recipient.toString().equals(r.getString(1))||!currency.equals(r.getString(2))||amount.compareTo(new BigDecimal(r.getString(3)))!=0||!fingerprint.equals(r.getString(4)))throw new SQLException("重复退款引用内容不同");
                        double current=balance(c,b.balance(),recipient,currency);c.rollback();return EconomyMutationResult.success(current);
                    }}
                }
                List<Paid> paid=new ArrayList<>();BigDecimal known=BigDecimal.ZERO;
                for(String id:sources)try(PreparedStatement s=c.prepareStatement("SELECT subject,payer,currency,paid,refunded FROM ezeconomy_spend_orders WHERE id=?"+(b.mysql()?" FOR UPDATE":""))){
                    s.setString(1,id);try(ResultSet r=s.executeQuery()){if(r.next()){
                        if(!currency.equals(r.getString(3)))throw new SQLException("原付款货币不同");
                        Paid row=new Paid(id,UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),new BigDecimal(r.getString(4)),new BigDecimal(r.getString(5)));paid.add(row);known=known.add(row.paid());
                    }}
                }
                if(known.compareTo(originalTotal)>0)throw new SQLException("原付款合计不匹配");
                BigDecimal target=amount.multiply(known).divide(originalTotal,8,RoundingMode.DOWN),remaining=target;
                for(int i=0;i<paid.size();i++){
                    Paid row=paid.get(i);BigDecimal adjustment=i==paid.size()-1?remaining:amount.multiply(row.paid()).divide(originalTotal,8,RoundingMode.DOWN);remaining=remaining.subtract(adjustment);
                    if(adjustment.signum()==0)continue;
                    if(row.refunded().add(adjustment).compareTo(row.paid())>0)throw new SQLException("原付款已退还，拒绝重复退款");
                    try(PreparedStatement s=c.prepareStatement("UPDATE ezeconomy_spend_orders SET refunded=? WHERE id=?")){s.setString(1,row.refunded().add(adjustment).toPlainString());s.setString(2,row.id());s.executeUpdate();}
                    BigDecimal total;try(PreparedStatement s=c.prepareStatement("SELECT amount FROM ezeconomy_spend WHERE subject=? AND payer=? AND currency=?"+(b.mysql()?" FOR UPDATE":""))){
                        s.setString(1,row.subject().toString());s.setString(2,row.payer().toString());s.setString(3,currency);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("缺少原归属汇总");total=new BigDecimal(r.getString(1));}}
                    if(total.compareTo(adjustment)<0)throw new SQLException("退款统计超过原归属消费");
                    try(PreparedStatement s=c.prepareStatement("UPDATE ezeconomy_spend SET amount=? WHERE subject=? AND payer=? AND currency=?")){
                        s.setBigDecimal(1,total.subtract(adjustment));s.setString(2,row.subject().toString());s.setString(3,row.payer().toString());s.setString(4,currency);s.executeUpdate();}
                }
                try(PreparedStatement s=c.prepareStatement("INSERT INTO ezeconomy_spend_refunds(id,payer,currency,amount,fingerprint) VALUES(?,?,?,?,?)")){
                    s.setString(1,refundId);s.setString(2,recipient.toString());s.setString(3,currency);s.setString(4,amount.toPlainString());s.setString(5,fingerprint);s.executeUpdate();}
                String upsert=b.mysql()?" ON DUPLICATE KEY UPDATE balance=balance+VALUES(balance)":" ON CONFLICT(id) DO UPDATE SET balance=balance+excluded.balance";
                if(credit)try(PreparedStatement s=c.prepareStatement("INSERT INTO "+safeTable(b.balance())+"(id,uuid,currency,balance) VALUES(?,?,?,?)"+upsert)){
                    s.setString(1,BalanceModel.idFor(recipient,currency));s.setString(2,recipient.toString());s.setString(3,currency);s.setDouble(4,value);s.executeUpdate();}
                double current=balance(c,b.balance(),recipient,currency);c.commit();committed=true;
                try{com.skyblockexp.ezeconomy.cache.CacheManager.getProvider().remove("bal:"+recipient+":"+currency);}catch(RuntimeException ignored){}
                return EconomyMutationResult.success(current);
            }finally{if(!committed)try{c.rollback();}catch(SQLException ignored){}try{c.setAutoCommit(true);}catch(SQLException error){if(!committed)throw error;}}
        });
    }
    public static EconomyMutationResult cache(EconomyMutationResult result,java.util.function.BiConsumer<String,Double> put,UUID payer,String currency){
        if(result!=null&&result.isSuccess())try{put.accept("bal:"+payer+":"+currency,result.getBalance());}catch(RuntimeException ignored){}return result;
    }
    /** Explicit money movement/administrative removal; not consumer spending. */
    public static boolean excludedWithdraw(StorageProvider storage,UUID payer,String currency,double amount){
        int before=EXCLUDED.get();EXCLUDED.set(before+1);
        try{return storage.tryWithdraw(payer,currency,amount);}finally{if(before==0)EXCLUDED.remove();else EXCLUDED.set(before);}
    }
}
