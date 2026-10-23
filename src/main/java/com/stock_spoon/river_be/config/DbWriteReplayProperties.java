package com.stock_spoon.river_be.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "db-write-replay")
public class DbWriteReplayProperties {
    private boolean enabled;
    private String queueUrl = "";
    private List<Integer> proxySqlErrorCodes =
            new ArrayList<>(List.of(9001, DbMigrationWriteFallbackClassifier.MYSQL_READ_ONLY_SERVER));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getQueueUrl() {
        return queueUrl;
    }

    public void setQueueUrl(String queueUrl) {
        this.queueUrl = queueUrl == null ? "" : queueUrl;
    }

    public List<Integer> getProxySqlErrorCodes() {
        return proxySqlErrorCodes;
    }

    public void setProxySqlErrorCodes(List<Integer> proxySqlErrorCodes) {
        this.proxySqlErrorCodes = proxySqlErrorCodes == null || proxySqlErrorCodes.isEmpty()
                ? List.of(9001, DbMigrationWriteFallbackClassifier.MYSQL_READ_ONLY_SERVER)
                : List.copyOf(proxySqlErrorCodes);
    }
}
