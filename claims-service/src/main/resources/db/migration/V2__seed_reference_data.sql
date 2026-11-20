-- Demo reference data. Policy administration is out of scope, so policies are seeded.
-- Fixed UUIDs so the dev login, tests and docs can refer to them.

INSERT INTO market (code, name, currency, timezone) VALUES
    ('SG', 'Singapore', 'SGD', 'Asia/Singapore'),
    ('HK', 'Hong Kong', 'HKD', 'Asia/Hong_Kong'),
    ('AU', 'Australia', 'AUD', 'Australia/Sydney'),
    ('MY', 'Malaysia',  'MYR', 'Asia/Kuala_Lumpur'),
    ('TH', 'Thailand',  'THB', 'Asia/Bangkok'),
    ('ID', 'Indonesia', 'IDR', 'Asia/Jakarta');

-- One claims team per market; new claims are routed to the team for the policy's market.
INSERT INTO team (id, name, market_code) VALUES
    ('10000000-0000-0000-0000-000000000001', 'Singapore Claims', 'SG'),
    ('10000000-0000-0000-0000-000000000002', 'Hong Kong Claims', 'HK'),
    ('10000000-0000-0000-0000-000000000003', 'Australia Claims', 'AU'),
    ('10000000-0000-0000-0000-000000000004', 'Malaysia Claims',  'MY'),
    ('10000000-0000-0000-0000-000000000005', 'Thailand Claims',  'TH'),
    ('10000000-0000-0000-0000-000000000006', 'Indonesia Claims', 'ID');

INSERT INTO app_user (id, role, name, email, team_id, authority_limit) VALUES
    -- claimants
    ('20000000-0000-0000-0000-000000000001', 'CLAIMANT', 'Mei Tan',       'mei.tan@example.com',       NULL, NULL),
    ('20000000-0000-0000-0000-000000000002', 'CLAIMANT', 'Jack Wilson',   'jack.wilson@example.com',   NULL, NULL),
    ('20000000-0000-0000-0000-000000000003', 'CLAIMANT', 'Ka Ho Chan',    'kaho.chan@example.com',     NULL, NULL),
    -- handlers
    ('30000000-0000-0000-0000-000000000001', 'HANDLER',  'Priya Nair',    'priya.nair@example.com',    '10000000-0000-0000-0000-000000000001', 20000.00),
    ('30000000-0000-0000-0000-000000000002', 'HANDLER',  'Daniel Lim',    'daniel.lim@example.com',    '10000000-0000-0000-0000-000000000001', 50000.00),
    ('30000000-0000-0000-0000-000000000003', 'HANDLER',  'Sophie Brown',  'sophie.brown@example.com',  '10000000-0000-0000-0000-000000000003', 30000.00),
    ('30000000-0000-0000-0000-000000000004', 'HANDLER',  'Wing Lee',      'wing.lee@example.com',      '10000000-0000-0000-0000-000000000002', 150000.00),
    -- regional manager (no team = all markets)
    ('40000000-0000-0000-0000-000000000001', 'MANAGER',  'Grace Ong',     'grace.ong@example.com',     NULL, NULL);

INSERT INTO policy (id, policy_number, holder_id, product_line, market_code, currency, sum_insured, deductible, effective_from, effective_to) VALUES
    ('50000000-0000-0000-0000-000000000001', 'SG-MOT-POL-0001', '20000000-0000-0000-0000-000000000001', 'MOTOR',    'SG', 'SGD',   80000.00,   500.00, '2026-01-01', '2027-01-01'),
    ('50000000-0000-0000-0000-000000000002', 'SG-PRP-POL-0002', '20000000-0000-0000-0000-000000000001', 'PROPERTY', 'SG', 'SGD',  600000.00,  1000.00, '2026-03-01', '2027-03-01'),
    ('50000000-0000-0000-0000-000000000003', 'AU-MOT-POL-0003', '20000000-0000-0000-0000-000000000002', 'MOTOR',    'AU', 'AUD',   45000.00,   750.00, '2026-02-15', '2027-02-15'),
    ('50000000-0000-0000-0000-000000000004', 'AU-PRP-POL-0004', '20000000-0000-0000-0000-000000000002', 'PROPERTY', 'AU', 'AUD',  900000.00,  2000.00, '2026-06-01', '2027-06-01'),
    ('50000000-0000-0000-0000-000000000005', 'HK-PRP-POL-0005', '20000000-0000-0000-0000-000000000003', 'PROPERTY', 'HK', 'HKD', 4000000.00,  5000.00, '2026-01-01', '2027-01-01');
