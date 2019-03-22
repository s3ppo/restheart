/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.handlers.sessions.txns;

import org.restheart.db.sessions.ClientSessionFactory;
import org.restheart.db.sessions.ClientSessionImpl;
import com.mongodb.MongoClient;
import io.undertow.server.HttpServerExchange;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.restheart.db.Database;
import org.restheart.db.DatabaseImpl;
import org.restheart.db.MongoDBClientSingleton;
import org.restheart.db.sessions.SessionsUtils;
import org.restheart.handlers.PipedHttpHandler;
import org.restheart.handlers.RequestContext;
import org.restheart.utils.HttpStatus;
import org.restheart.utils.ResponseHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * commits the transaction of the session
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class GetTxnHandler extends PipedHttpHandler {
    private static final Logger LOGGER = LoggerFactory
            .getLogger(GetTxnHandler.class);

    private static MongoClient MCLIENT = MongoDBClientSingleton
            .getInstance().getClient();

    /**
     * Creates a new instance of PatchTxnHandler
     */
    public GetTxnHandler() {
        super();
    }

    public GetTxnHandler(PipedHttpHandler next) {
        super(next, new DatabaseImpl());
    }

    public GetTxnHandler(PipedHttpHandler next, Database dbsDAO) {
        super(next, dbsDAO);
    }

    /**
     *
     * @param exchange
     * @param context
     * @throws Exception
     */
    @Override
    public void handleRequest(
            HttpServerExchange exchange,
            RequestContext context)
            throws Exception {
        if (context.isInError()) {
            next(exchange, context);
            return;
        }

        String _sid = context.getSid();

        UUID sid;

        try {
            sid = UUID.fromString(_sid);
        } catch (IllegalArgumentException iae) {
            ResponseHelper.endExchangeWithMessage(
                    exchange,
                    context,
                    HttpStatus.SC_NOT_ACCEPTABLE,
                    "Invalid session id");
            next(exchange, context);
            return;
        }

        var txn = SessionsUtils.getTxnServerStatus(sid);

        var currentTxn = new BsonDocument();

        var resp = new BsonDocument("currentTxn", currentTxn);
        
        currentTxn.append("id",
                txn.getTxnId() > Integer.MAX_VALUE
                ? new BsonInt64(txn.getTxnId())
                : new BsonInt32((int) txn.getTxnId()));

        context.setResponseStatusCode(HttpStatus.SC_NOT_FOUND);
        currentTxn.append("state", new BsonString(txn.getState().name()));
        context.setResponseStatusCode(HttpStatus.SC_OK);

        
        context.setResponseContent(resp);

        next(exchange, context);
    }

}
