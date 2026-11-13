-- Cross-row derived columns that the generator cannot fill in one pass (portable MySQL / PostgreSQL SQL).
UPDATE trd_order_item SET refunded_qty = COALESCE((
    SELECT SUM(ri.quantity) FROM trd_refund_item ri JOIN trd_refund r ON r.id = ri.refund_id
    WHERE ri.order_item_id = trd_order_item.id AND r.refund_status = 'SUCCESS'), 0);
UPDATE trd_order_item SET item_status = CASE WHEN refunded_qty = 0 THEN 'NORMAL'
    WHEN refunded_qty >= quantity THEN 'REFUNDED' ELSE 'PARTIAL_REFUNDED' END;
UPDATE usr_member SET
    total_paid_amount = COALESCE((SELECT SUM(o.pay_amount) FROM trd_order o WHERE o.member_id = usr_member.id AND o.paid_at IS NOT NULL), 0),
    total_order_cnt = (SELECT COUNT(*) FROM trd_order o WHERE o.member_id = usr_member.id AND o.paid_at IS NOT NULL),
    last_order_at = (SELECT MAX(o.created_at) FROM trd_order o WHERE o.member_id = usr_member.id);
UPDATE trd_payment SET is_refunded = CASE WHEN EXISTS (SELECT 1 FROM trd_refund r WHERE r.order_id = trd_payment.order_id AND r.refund_status = 'SUCCESS') THEN 1 ELSE 0 END,
    currency_code = 'CNY';
