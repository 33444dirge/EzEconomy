package com.skyblockexp.ezeconomy.service.spending;

import java.sql.*;
import java.math.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import com.skyblockexp.ezeconomy.storage.jaloquent.model.BalanceModel;

/** Real SQLite transactions: no fake balances or mocks of SQL commit behavior. */
public final class LedgerRegression {
    private static final UUID P=UUID.randomUUID(),A=UUID.randomUUID(),B=UUID.randomUUID();
    private static int assertions;private static boolean mysql;
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    static void eq(BigDecimal a,String expected,String what){check(a.compareTo(new BigDecimal(expected))==0,what+": "+a);}
    static BigDecimal scalar(Connection c,String sql)throws Exception {try(var s=c.createStatement();var r=s.executeQuery(sql)){return r.next()?new BigDecimal(r.getString(1)):BigDecimal.ZERO;}}
    static BigDecimal spent(Connection c,UUID subject)throws Exception{return scalar(c,"SELECT amount FROM ezeconomy_spend WHERE subject='"+subject+"'");}
    static BigDecimal balance(Connection c)throws Exception{return scalar(c,"SELECT balance FROM balances WHERE id='"+BalanceModel.idFor(P,"j")+"'");}
    static void bind(Connection c)throws Exception{
        if(mysql)SqlSpendingLedger.mysql(null,null,c,"balances");else SqlSpendingLedger.sqlite(null,c,c,"balances");
        var schema=SqlSpendingLedger.class.getDeclaredMethod("schema",Connection.class,boolean.class);schema.setAccessible(true);schema.invoke(null,c,mysql);
        var type=Class.forName(SqlSpendingLedger.class.getName()+"$Binding");var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var f=SqlSpendingLedger.class.getDeclaredField("BINDING");f.setAccessible(true);((AtomicReference<Object>)f.get(null)).set(constructor.newInstance(new Object(),(Function<UUID,UUID>)id->A,"j"));
        f=SqlSpendingLedger.class.getDeclaredField("prepared");f.setAccessible(true);f.setBoolean(null,true);
    }
    public static void main(String[] args)throws Exception{
        mysql=args.length>0&&args[0].equals("mysql");Class.forName(mysql?"org.h2.Driver":"org.sqlite.JDBC");
        Class.forName("com.skyblockexp.ezeconomy.storage.mysql.MySQLBalanceDao");
        Class.forName("com.skyblockexp.ezeconomy.storage.SQLiteStorageProvider");
        try(Connection c=DriverManager.getConnection(mysql?"jdbc:h2:mem:ledger;MODE=MySQL;DATABASE_TO_LOWER=TRUE":"jdbc:sqlite::memory:")){
            c.createStatement().executeUpdate("CREATE TABLE balances(id TEXT PRIMARY KEY,uuid TEXT,currency TEXT,balance REAL)");
            try(var s=c.prepareStatement("INSERT INTO balances VALUES(?,?,?,1000)")){s.setString(1,BalanceModel.idFor(P,"j"));s.setString(2,P.toString());s.setString(3,"j");s.executeUpdate();}
            bind(c);
            var dao=mysql?new com.skyblockexp.ezeconomy.storage.mysql.MySQLBalanceDao(null,"balances",null,c,new com.skyblockexp.ezeconomy.storage.mysql.StripedLockManager(16),key->null,(key,value)->{},()->false,null):null;
            if(mysql)bind(c);
            try(var scope=SqlSpendingLedger.order("test/a")){check((mysql?dao.withdrawAndGetBalance(P,"j",100):SqlSpendingLedger.withdraw(c,"balances",mysql,P,A,"j",100)).isSuccess(),"debit");}
            eq(spent(c,A),"100","first contribution");eq(balance(c),"900","debit balance");
            try(var scope=SqlSpendingLedger.order("test/a")){check(SqlSpendingLedger.withdraw(c,"balances",mysql,P,B,"j",100).isSuccess(),"idempotent order after moving");}
            eq(balance(c),"900","no double charge");eq(spent(c,B),"0","old charge stays in A");
            SqlSpendingLedger.refund("refund/a",List.of("test/a"),new BigDecimal("100"),P,"j",50);
            eq(spent(c,A),"50","refund original subject");eq(spent(c,B),"0","no new-subject deduction");eq(balance(c),"950","refund credit");
            SqlSpendingLedger.refund("refund/a",List.of("test/a"),new BigDecimal("100"),P,"j",50);eq(balance(c),"950","no duplicate refund credit");
            boolean rejected=false;try{SqlSpendingLedger.refund("refund/a",List.of("other"),new BigDecimal("100"),P,"j",50);}catch(SQLException expected){rejected=true;}check(rejected,"mismatched refund reference rejected");
            try(var scope=SqlSpendingLedger.order("test/b")){check(SqlSpendingLedger.withdraw(c,"balances",mysql,P,B,"j",50).isSuccess(),"new-subject debit");}eq(spent(c,B),"50","new consumption in B");
            SqlSpendingLedger.refund("refund/old",List.of("not-in-new-ledger"),new BigDecimal("100"),P,"j",85);eq(spent(c,B),"50","legacy refund never steals new consumption");eq(spent(c,A),"50","legacy refund never steals old tracked contribution");
            SqlSpendingLedger.refund("refund/partial",List.of("test/a"),new BigDecimal("100"),P,"j",35);eq(spent(c,A),"15","second partial refund");
            SqlSpendingLedger.refund("refund/mixed",List.of("test/a","legacy/a"),new BigDecimal("200"),P,"j",10);eq(spent(c,A),"10","known and legacy proportional refund");
            BigDecimal before=balance(c);rejected=false;try{SqlSpendingLedger.refund("refund/over",List.of("test/a"),new BigDecimal("100"),P,"j",20);}catch(SQLException expected){rejected=true;}check(rejected,"over-refund blocked");check(before.compareTo(balance(c))==0,"failed refund cannot credit");eq(spent(c,A),"10","failed refund rolls back contribution");
            SqlSpendingLedger.acknowledgeRefund("manual/b",List.of("test/b"),new BigDecimal("50"),P,"j",50);check(before.compareTo(balance(c))==0,"manual acknowledgment cannot credit twice");eq(spent(c,B),"0","manual refund restores original contribution");
            try(var scope=SqlSpendingLedger.order("test/poor")){check(!SqlSpendingLedger.withdraw(c,"balances",mysql,P,A,"j",100000).isSuccess(),"insufficient balance rejected");}eq(spent(c,A),"10","declined debit not counted");
            // A stats write failure must roll the preceding balance subtraction back.
            if(!mysql){c.createStatement().executeUpdate("CREATE TRIGGER fail_stats BEFORE INSERT ON ezeconomy_spend BEGIN SELECT RAISE(ABORT,'injected'); END");
            rejected=false;try(var scope=SqlSpendingLedger.order("test/rollback")){SqlSpendingLedger.withdraw(c,"balances",mysql,P,A,"j",3);}catch(SQLException expected){rejected=true;}check(rejected,"stats failure surfaced");check(before.compareTo(balance(c))==0,"debit rolled back with stats");
            c.createStatement().executeUpdate("DROP TRIGGER fail_stats");}
            ExecutorService workers=Executors.newFixedThreadPool(4);try{
                List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<100;i++){final int number=i;jobs.add(workers.submit(()->{synchronized(c){try(var scope=SqlSpendingLedger.order("parallel/"+number)){if(!SqlSpendingLedger.withdraw(c,"balances",mysql,P,A,"j",.1).isSuccess())throw new AssertionError("parallel debit");}catch(Exception e){throw new RuntimeException(e);}}}));}
                for(var job:jobs)job.get(10,TimeUnit.SECONDS);
            }finally{workers.shutdownNow();}eq(spent(c,A),"20","exact decimal aggregate under concurrent calls");
            var snap=SqlSpendingLedger.snapshot(100);check(snap.rows().stream().map(SqlSpendingLedger.Row::amount).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(new BigDecimal("20"))==0,"snapshot total agrees with detail");
            if(mysql){
                var provider=(com.skyblockexp.ezeconomy.api.storage.StorageProvider)Proxy.newProxyInstance(LedgerRegression.class.getClassLoader(),new Class<?>[]{com.skyblockexp.ezeconomy.api.storage.StorageProvider.class},(proxy,method,a)->{if(method.getName().equals("tryWithdraw"))return dao.withdrawAndGetBalance((UUID)a[0],(String)a[1],(double)a[2]).isSuccess();throw new UnsupportedOperationException(method.getName());});
                check(SqlSpendingLedger.excludedWithdraw(provider,P,"j",1),"excluded administrative debit still settles");eq(spent(c,A),"20","excluded transfer or admin debit is not consumption");
            }
            SqlSpendingLedger.close();check(!SqlSpendingLedger.ready(),"shutdown detaches resolver");
            bind(c);eq(SqlSpendingLedger.snapshot(100).rows().stream().filter(row->row.subject().equals(A)).findFirst().orElseThrow().amount(),"20","reattach keeps persistent totals");
            SqlSpendingLedger.close();
        }
        System.out.println("LEDGER_REGRESSION_PASS backend="+(mysql?"H2-MySQL":"SQLite")+" assertions="+assertions+" concurrent_debits=100; bytecode verification, moving subjects, linked/partial/legacy/manual/repeated refunds and SQL rollback");
    }
}
