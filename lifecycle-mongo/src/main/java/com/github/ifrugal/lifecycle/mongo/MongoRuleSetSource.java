package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;
import com.github.ifrugal.lifecycle.core.registry.StoredRuleSetSource;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Rules stored as documents in {@code lifecycleRuleSet} (DD-05). Reads {@code status: ACTIVE} rows only; see
 * {@link MongoRuleSetAdmin} for the governance side (draft, activate, retire). */
public final class MongoRuleSetSource extends StoredRuleSetSource {

    private final MongoCollection<Document> ruleSetCollection;

    public MongoRuleSetSource(MongoDatabase database, RuleSetParser parser) {
        super(parser);
        this.ruleSetCollection = Objects.requireNonNull(database, "database").getCollection(MongoCollections.RULE_SET);
    }

    @Override
    protected List<StoredRuleSet> fetchActive() {
        List<StoredRuleSet> out = new ArrayList<>();
        for (Document d : ruleSetCollection.find(Filters.eq("status", "ACTIVE"))) {
            out.add(new StoredRuleSet(d.getString("tenantId"), d.getString("entityType"),
                    d.getInteger("version"), d.getString("format"), d.getString("body")));
        }
        return out;
    }
}
