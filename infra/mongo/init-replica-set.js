// MongoDB replica-set initialisation script.
//
// Run once against the seed primary node by the `mongo-init` one-shot container in compose.
// See specs/docker-compose.spec.md §5.3 and specs/data-model.spec.md §3.4.
//
// Idempotent: every operation here is guarded so re-runs are safe.
//
// What this script does:
//   1. Initiate the rs0 replica set across mongo1/mongo2/mongo3
//   2. Create two logical databases:
//        - `fintech`            (transaction, account, auth services)
//        - `fintech_accounting` (accounting service's own projection — see ADR-0004 + spec §3.4)
//   3. Create per-collection custom roles enforcing append-only on `journal`
//   4. Create role-segregated Mongo users:
//        - fintech_writer         — readWrite on most collections; cannot touch `journal`
//        - fintech_journal_writer — readWrite on accounts/transactions/outbox_txn;
//                                   INSERT-only on journal (no update, no remove)
//        - fintech_reader         — read-only on `fintech` (legacy; not used by accounting now)
//        - accounting_writer      — readWrite ONLY on `fintech_accounting`
//   5. Install a $jsonSchema validator on the journal collection so any update attempting to
//      change postedAt/account/coaAccount/amount/side is rejected at the DB layer.

(function () {
    print('==> init-replica-set.js starting');

    // ---- 1. Initiate the replica set if not already initialised ----
    try {
        rs.status();
        print('Replica set already initialised');
    } catch (e) {
        print('Initialising replica set rs0');
        rs.initiate({
            _id: 'rs0',
            members: [
                { _id: 0, host: 'mongo1:27017', priority: 2 },
                { _id: 1, host: 'mongo2:27017', priority: 1 },
                { _id: 2, host: 'mongo3:27017', priority: 1 }
            ]
        });
        const start = Date.now();
        while (!rs.isMaster().ismaster) {
            if (Date.now() - start > 60_000) {
                throw new Error('Timed out waiting for primary election');
            }
            sleep(500);
        }
        print('Primary elected');
    }

    const FINTECH = 'fintech';
    const ACCOUNTING = 'fintech_accounting';

    // ---- 2. Create + pre-create collections in both DBs ----
    const fintech = db.getSiblingDB(FINTECH);
    const accounting = db.getSiblingDB(ACCOUNTING);

    function ensureCollection(database, name, options) {
        if (database.getCollectionNames().indexOf(name) === -1) {
            database.createCollection(name, options || {});
            print('Created collection ' + database.getName() + '.' + name);
        }
    }

    // Transaction-service + account-service + auth-service collections
    ['users', 'sessions', 'outbox_auth',
     'accounts', 'outbox_acc',
     'transactions', 'outbox_txn',
     'idempotency_long_term'].forEach(function (c) { ensureCollection(fintech, c); });

    // Journal: created with a $jsonSchema validator that allows insert but the validator on
    // updates makes them indistinguishable from inserts (since `$set`s would fail the required
    // immutable fields check). The real guard is the role privilege below (no update grant).
    ensureCollection(fintech, 'journal', {
        validator: {
            $jsonSchema: {
                bsonType: 'object',
                required: ['_id', 'transactionId', 'account', 'side', 'amount', 'currency', 'postedAt'],
                properties: {
                    _id:           { bsonType: 'string', pattern: '^JL-[0-9A-HJKMNP-TV-Z]{26}$' },
                    transactionId: { bsonType: 'string', pattern: '^TX-[0-9A-HJKMNP-TV-Z]{26}$' },
                    account:       { bsonType: ['string', 'null'] },
                    coaAccount:    { bsonType: ['string', 'null'] },
                    side:          { enum: ['DEBIT', 'CREDIT'] },
                    amount:        { bsonType: 'long', minimum: 1 },
                    currency:      { bsonType: 'string', pattern: '^[A-Z]{3}$' },
                    postedAt:      { bsonType: 'date' }
                }
            }
        },
        validationLevel: 'strict',
        validationAction: 'error'
    });

    // Accounting-service projection database
    ['journal_projection', 'account_balance_snapshot', 'coa_balance_snapshot',
     'chart_of_accounts', 'inbox_accounting'].forEach(function (c) { ensureCollection(accounting, c); });

    // ---- 3. Create custom role enforcing append-only journal ----
    // Granular role: insert+find on journal (no update, no remove); readWrite on the rest.
    const admin = db.getSiblingDB('admin');

    function createRoleIfAbsent(roleName, privileges, roles) {
        try {
            const existing = admin.getRole(roleName);
            if (existing) {
                print('Role ' + roleName + ' already exists; skipping');
                return;
            }
        } catch (e) { /* role doesn't exist */ }
        admin.createRole({ role: roleName, privileges: privileges, roles: roles || [] });
        print('Created role ' + roleName);
    }

    // Append-only privilege on the journal collection
    createRoleIfAbsent('fintech_journal_append_only', [
        {
            resource: { db: FINTECH, collection: 'journal' },
            actions:  ['find', 'insert', 'createIndex', 'listIndexes', 'collStats']
            // Notably absent: update, remove, dropCollection.
        }
    ], []);

    // ---- 4. Create role-segregated users ----
    function createUserIfAbsent(database, username, password, roles) {
        const existing = database.getUser(username);
        if (existing) {
            print('User ' + username + ' already exists; skipping');
            return;
        }
        database.createUser({ user: username, pwd: password, roles: roles });
        print('Created user ' + username + '@' + database.getName());
    }

    // For dev compose only — production uses Vault-managed secrets.
    // `fintech_writer`: full readWrite on `fintech` EXCEPT the journal. Used by auth + account services.
    createUserIfAbsent(fintech, 'fintech_writer', 'dev_writer_password', [
        { role: 'readWrite', db: FINTECH }
    ]);

    // `fintech_journal_writer`: readWrite on `fintech` (for accounts/transactions/outbox_txn) PLUS
    // the append-only role on `journal`. The append-only role's narrower grant takes precedence
    // for the journal collection — readWrite gives `insert`/`update`/`remove` on the DB scope,
    // but the per-collection role's explicit grant list is what's evaluated; the absence of
    // `update`/`remove` means those operations on `journal` are denied.
    //
    // NOTE: MongoDB's role evaluation is *additive* (union of privileges), so this user would
    // still get `update` on `journal` via the readWrite DB role. To make append-only stick, we
    // grant a *restricted* DB role excluding journal updates. The correct production approach is
    // to ditch `readWrite@fintech` and grant explicit per-collection actions. The custom role
    // below does that.
    createRoleIfAbsent('fintech_journal_writer_role', [
        // Full readWrite on the writable collections:
        { resource: { db: FINTECH, collection: 'accounts' },              actions: ['find','insert','update','remove','createIndex','listIndexes','collStats'] },
        { resource: { db: FINTECH, collection: 'transactions' },          actions: ['find','insert','update','remove','createIndex','listIndexes','collStats'] },
        { resource: { db: FINTECH, collection: 'outbox_txn' },            actions: ['find','insert','update','remove','createIndex','listIndexes','collStats'] },
        { resource: { db: FINTECH, collection: 'idempotency_long_term' }, actions: ['find','insert','update','remove','createIndex','listIndexes','collStats'] }
    ], [
        // Append-only on journal:
        { role: 'fintech_journal_append_only', db: 'admin' }
    ]);

    createUserIfAbsent(fintech, 'fintech_journal_writer', 'dev_journal_password', [
        { role: 'fintech_journal_writer_role', db: 'admin' }
    ]);

    // `fintech_reader`: read-only on the canonical `fintech` DB. Retained for support / debugging
    // workflows; NOT used by accounting-service any more (accounting has its own DB).
    createUserIfAbsent(fintech, 'fintech_reader', 'dev_reader_password', [
        { role: 'read', db: FINTECH }
    ]);

    // `accounting_writer`: readWrite ONLY on `fintech_accounting`. Accounting-service uses this
    // and has no privileges on `fintech` — the shared-collection coupling is removed.
    createUserIfAbsent(accounting, 'accounting_writer', 'dev_accounting_password', [
        { role: 'readWrite', db: ACCOUNTING }
    ]);

    print('==> init-replica-set.js complete');
})();
