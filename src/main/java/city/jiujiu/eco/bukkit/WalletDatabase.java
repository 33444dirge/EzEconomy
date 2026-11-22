package city.jiujiu.eco.bukkit;

import city.jiujiu.eco.storage.Accounts;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.bukkit.configuration.file.YamlConfiguration;

final class WalletDatabase {
    private WalletDatabase() {}

    static Accounts open(JiuJiuEco plugin) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File(plugin.getDataFolder(), "config.yml"));
        String backend = config.getString("storage", "sqlite");
        boolean mysql = "mysql".equalsIgnoreCase(backend);
        Accounts.Connections connections;
        if (mysql) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String url = "jdbc:mysql://" + config.getString("mysql.host", "localhost") + ":"
                    + config.getInt("mysql.port", 3306) + "/" + config.getString("mysql.database", "jiujiueco")
                    + "?" + config.getString("mysql.jdbc-params", "useSSL=true");
            connections = () -> DriverManager.getConnection(url, config.getString("mysql.username"), config.getString("mysql.password"));
        } else if ("sqlite".equalsIgnoreCase(backend)) {
            Class.forName("org.sqlite.JDBC");
            File file = new File(plugin.getDataFolder(), config.getString("sqlite.file", "jiujiueco.db"));
            connections = () -> DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        } else {
            throw new IllegalArgumentException("storage 须为 sqlite 或 mysql");
        }
        Connection db = connections.open();
        try {
            return new Accounts(db, mysql, connections);
        } catch (SQLException | RuntimeException failure) {
            db.close();
            throw failure;
        }
    }
}
