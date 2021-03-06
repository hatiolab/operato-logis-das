INSERT INTO JOB_INSTANCES (
	ID,
	BATCH_ID,
	JOB_DATE,
	JOB_SEQ,
	JOB_TYPE,
	STAGE_CD,
	COM_CD,
	EQUIP_TYPE,
	EQUIP_GROUP_CD,
	EQUIP_CD,
	SUB_EQUIP_CD,
	CLASS_CD,
	BOX_CLASS_CD,
	SHOP_CD,
	SHOP_NM,
	CUST_ORDER_NO,
	ORDER_NO,
	INVOICE_ID,
	SKU_CD,
	SKU_BARCD,
	SKU_NM,
	BOX_IN_QTY,
	PICK_QTY,
	PICKING_QTY,
	PICKED_QTY,
	INSPECTED_QTY,
	ORDER_TYPE,
	STATUS,
	DOMAIN_ID,
	CREATOR_ID,
	UPDATER_ID,
	CREATED_AT,
	UPDATED_AT
)
SELECT
	uuid_generate_v4()::text ID,
	BATCH_ID,
	JOB_DATE,
	JOB_SEQ,
	JOB_TYPE,
	STAGE_CD,
	COM_CD,
	EQUIP_TYPE,
	EQUIP_GROUP_CD,
	EQUIP_CD,
	SUB_EQUIP_CD,
	CLASS_CD,
	BOX_CLASS_CD,
	MAX(SHOP_CD) AS SHOP_CD,
	MAX(SHOP_NM) AS SHOP_NM,
	MAX(CUST_ORDER_NO) AS CUST_ORDER_NO,
	MAX(ORDER_NO) AS ORDER_NO,
	INVOICE_ID,
	SKU_CD,
	MAX(SKU_BARCD) AS SKU_BARCD,
	MAX(SKU_NM) AS SKU_NM,
	MAX(BOX_IN_QTY) AS BOX_IN_QTY,
	SUM(ORDER_QTY) AS PICK_QTY,
	0 AS PICKING_QTY,
	0 AS PICKED_QTY,
	0 AS INSPECTED_QTY,
	MAX(ORDER_TYPE) AS ORDER_TYPE,
	'W',
	DOMAIN_ID,
	'system',
	'system',
	sysdate,
	sysdate
FROM
	ORDERS
WHERE
	DOMAIN_ID = :domainId
	AND BATCH_ID  = :batchId
GROUP BY
	DOMAIN_ID,
	BATCH_ID,
	JOB_DATE,
	JOB_SEQ,
	JOB_TYPE,
	STAGE_CD,
	COM_CD,
	EQUIP_TYPE,
	EQUIP_GROUP_CD,
	EQUIP_CD,
	SUB_EQUIP_CD,
	CLASS_CD,
	BOX_CLASS_CD,
	SKU_CD,
	INVOICE_ID