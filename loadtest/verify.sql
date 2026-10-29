-- Invariants that must hold after any load run. Every check should return zero rows.
\echo 'cells where reserved exceeds capacity (oversell):'
SELECT h3_index, capacity, reserved FROM cells WHERE reserved > capacity;

\echo 'cells whose reserved counter disagrees with live orders:'
SELECT c.h3_index, c.reserved, count(o.id) AS live_orders
FROM cells c LEFT JOIN orders o ON o.h3_index = c.h3_index AND o.status <> 'CANCELLED'
GROUP BY c.h3_index, c.reserved
HAVING c.reserved <> count(o.id);

\echo 'cells whose waitlist positions are not exactly 1..waitlist_next (gaps or duplicates):'
SELECT c.h3_index, c.waitlist_next, count(w.id) AS entries, count(DISTINCT w.position) AS distinct_positions
FROM cells c LEFT JOIN waitlist_entries w ON w.h3_index = c.h3_index
GROUP BY c.h3_index, c.waitlist_next
HAVING c.waitlist_next <> count(w.id)
    OR count(w.id) <> count(DISTINCT w.position)
    OR (count(w.id) > 0 AND (min(w.position) <> 1 OR max(w.position) <> c.waitlist_next));

\echo 'cells with a free slot AND people waiting (queue jumped):'
SELECT h3_index, capacity, reserved, waitlist_next - waitlist_promoted AS waiting
FROM cells WHERE reserved < capacity AND waitlist_next > waitlist_promoted;

\echo 'totals:'
SELECT (SELECT count(*) FROM cells) AS cells,
       (SELECT count(*) FROM orders) AS orders,
       (SELECT count(*) FROM orders WHERE status = 'CONFIRMED') AS confirmed,
       (SELECT count(*) FROM orders WHERE payment_status = 'PENDING') AS pending_payment,
       (SELECT count(*) FROM waitlist_entries) AS waitlist_entries,
       (SELECT count(*) FROM idempotency_keys) AS idempotency_keys;
