
CREATE UNIQUE INDEX uq_wallet_entries_payment_type
    ON wallet.wallet_entries (payment_id, type);
