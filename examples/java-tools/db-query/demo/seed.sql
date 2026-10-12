-- Copyright 2026 OLO Labs
-- SPDX-License-Identifier: Apache-2.0
-- A small fictional sales database for the db-query example. Run it as the database admin (see README.md); it
-- creates the tables and a read-only account, reader, for the tool.
CREATE TABLE customers (
    id INTEGER PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    city VARCHAR(60) NOT NULL,
    segment VARCHAR(20) NOT NULL
);
CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    ordered_on DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL
);
INSERT INTO customers VALUES
    (1, 'Acme Corp', 'Berlin', 'enterprise'),
    (2, 'Globex', 'Pune', 'enterprise'),
    (3, 'Initech', 'Austin', 'mid-market'),
    (4, 'Umbrella Retail', 'Pune', 'smb');
INSERT INTO orders VALUES
    (1001, 1, DATE '2026-09-02', 'SHIPPED', 1200.00),
    (1002, 2, DATE '2026-09-05', 'SHIPPED', 860.50),
    (1003, 1, DATE '2026-09-19', 'CANCELLED', 300.00),
    (1004, 3, DATE '2026-10-01', 'PENDING', 145.25),
    (1005, 4, DATE '2026-10-03', 'SHIPPED', 99.99),
    (1006, 2, DATE '2026-10-08', 'PENDING', 2400.00);
CREATE USER reader PASSWORD 'change-me';
GRANT SELECT ON customers, orders TO reader;
