package com.skyblockexp.ezeconomy.service.spending;

import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import com.skyblockexp.ezeconomy.storage.mysql.*;
import com.skyblockexp.ezeconomy.storage.jaloquent.model.BalanceModel;
import com.skyblockexp.ezeconomy.storage.MySQLStorageProvider;

/** Pauses a real SQL transaction, then attempts a real legacy DAO deposit on another worker. */
public final class SharedConnectionRegression {
    private static void field(Object target,String name,Object value)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static MySQLStorageProvider provider(Connection c)throws Exception{
        var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var p=(MySQLStorageProvider)((sun.misc.Unsafe)f.get(null)).allocateInstance(MySQLStorageProvider.class);
        field(p,"connection",c);field(p,"table","balances");field(p,"lock",new Object());
        com.skyblockexp.ezeconomy.storage.jaloquent.EzTableRegistry.registerAll("balances", "players", "transactions");
        var jdbc=new com.skyblockexp.ezeconomy.storage.jaloquent.EzJdbcStore(c);
        field(p,"balanceRepo",new com.github.ezframework.jaloquent.model.ModelRepository<BalanceModel>(jdbc,BalanceModel.PREFIX,BalanceModel::new,com.github.ezframework.javaquerybuilder.query.sql.SqlDialect.MYSQL));return p;
    }
    public static void main(String[] args)throws Exception {
        UUID payer=UUID.randomUUID(),subject=UUID.randomUUID(),recipient=UUID.randomUUID();
        CountDownLatch paused=new CountDownLatch(1),release=new CountDownLatch(1),entered=new CountDownLatch(1);
        try(Connection real=DriverManager.getConnection("jdbc:h2:mem:shared;MODE=MySQL;DATABASE_TO_LOWER=TRUE")){
            real.createStatement().execute("CREATE TABLE balances(id VARCHAR(69) PRIMARY KEY,uuid VARCHAR(36),currency VARCHAR(32),balance DOUBLE)");
            try(var s=real.prepareStatement("INSERT INTO balances VALUES(?,?,?,1000)")){s.setString(1,BalanceModel.idFor(payer,"j"));s.setString(2,payer.toString());s.setString(3,"j");s.executeUpdate();}
            Method schema=SqlSpendingLedger.class.getDeclaredMethod("schema",Connection.class,boolean.class);schema.setAccessible(true);schema.invoke(null,real,true);
            Connection c=(Connection)Proxy.newProxyInstance(SharedConnectionRegression.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,m,a)->{
                if(m.getName().equals("prepareStatement")&&a[0].toString().startsWith("INSERT INTO ezeconomy_spend_orders")){
                    paused.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new SQLException("test timeout");throw new SQLException("injected statistics failure");
                }
                try{return m.invoke(real,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
            var dao=new MySQLBalanceDao(null,"balances",null,c,new StripedLockManager(16),key->null,(key,value)->{},()->false,null);
            var provider=provider(c);
            ExecutorService workers=Executors.newFixedThreadPool(5);
            try{
                Future<?> debit=workers.submit(()->{synchronized(c){try{SqlSpendingLedger.withdraw(c,"balances",true,payer,subject,"j",100);throw new AssertionError("must fail");}catch(SQLException expected){if(!expected.getMessage().equals("injected statistics failure"))throw new RuntimeException(expected);}}});
                if(!paused.await(5,TimeUnit.SECONDS))throw new AssertionError("debit did not pause");
                Future<?> deposit=workers.submit(()->{entered.countDown();if(!dao.depositAndGetBalance(payer,"j",50).isSuccess())throw new AssertionError("deposit failed");});
                if(!entered.await(5,TimeUnit.SECONDS))throw new AssertionError("deposit worker did not start");
                Future<?> transfer=workers.submit(()->{if(!provider.transfer(payer,recipient,"j",30,30).isSuccess())throw new AssertionError("transfer failed");});
                Future<Double> read=workers.submit(()->provider.getBalance(payer,"j"));
                Future<?> repository=workers.submit(()->{try{new com.skyblockexp.ezeconomy.storage.jaloquent.EzJdbcStore(c).executeUpdate("UPDATE balances SET balance=balance+7 WHERE id=?",List.of(BalanceModel.idFor(payer,"j")));}catch(Exception e){throw new RuntimeException(e);}});
                boolean serialized;
                try{deposit.get(300,TimeUnit.MILLISECONDS);serialized=false;}catch(TimeoutException expected){serialized=true;}
                try{transfer.get(100,TimeUnit.MILLISECONDS);serialized=false;}catch(TimeoutException expected){}
                try{read.get(100,TimeUnit.MILLISECONDS);serialized=false;}catch(TimeoutException expected){}
                try{repository.get(100,TimeUnit.MILLISECONDS);serialized=false;}catch(TimeoutException expected){}
                release.countDown();debit.get(5,TimeUnit.SECONDS);deposit.get(5,TimeUnit.SECONDS);transfer.get(5,TimeUnit.SECONDS);read.get(5,TimeUnit.SECONDS);repository.get(5,TimeUnit.SECONDS);
                double balance=provider.getBalance(payer,"j");
                System.out.println("SHARED_CONNECTION serialized="+serialized+" final_balance="+balance+" expected=1027 recipient="+provider.getBalance(recipient,"j"));
                if(!serialized||balance!=1027||provider.getBalance(recipient,"j")!=30)throw new AssertionError("another operation joined unrelated debit rollback");
                if(provider.transfer(recipient,payer,"j",31,31).isSuccess())throw new AssertionError("insufficient transfer accepted");
                if(provider.getBalance(recipient,"j")!=30)throw new AssertionError("declined transfer changed funds");
                if(!provider.transfer(recipient,recipient,"j",5,4).isSuccess()||provider.getBalance(recipient,"j")!=29)throw new AssertionError("self transfer fee incorrect");
                try(var s=real.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM ezeconomy_spend")){r.next();if(r.getInt(1)!=0)throw new AssertionError("transfer counted as consumption");}
                System.out.println("SHARED_CONNECTION_REGRESSION_PASS");
            }finally{release.countDown();workers.shutdownNow();}
        }finally{SqlSpendingLedger.close();}
    }
}
