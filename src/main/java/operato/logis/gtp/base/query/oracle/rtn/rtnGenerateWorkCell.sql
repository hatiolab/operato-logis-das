INSERT INTO JOB_INSTANCES (
      ID,
      BATCH_ID,
      JOB_DATE,
      JOB_SEQ,
      JOB_TYPE,
      COM_CD,
      SHOP_CD,
      SHOP_NM,
      EQUIP_TYPE,
      EQUIP_CD,
      EQUIP_NM,
      SUB_EQUIP_CD,
      ORDER_NO,
      SKU_CD,
      SKU_NM,
      BOX_TYPE_CD,
      BOX_ID,
      INVOICE_ID, 
      BOX_IN_QTY, 
      PICKED_QTY, 
      ORDER_TYPE,
      DOMAIN_ID,
      CREATOR_ID,
      UPDATER_ID,
      CREATED_AT,
      UPDATED_AT
 ) 
SELECT 
	  F_GET_GENERATE_UUID(),
	  BATCH_ID,
	  JOB_DATE,
	  JOB_SEQ,
	  JOB_TYPE,
	  COM_CD,
	  SHOP_CD,
	  SHOP_NM,
	  EQUIP_TYPE,
	  EQUIP_CD,
	  EQUIP_NM,
	  SUB_EQUIP_CD,
	  ORDER_NO,
	  SKU_CD,
	  SKU_NM,
	  BOX_TYPE_CD,
	  BOX_ID,
	  INVOICE_ID, 
	  BOX_IN_QTY, 
	  PICKED_QTY, 
	  ORDER_TYPE,
	  DOMAIN_ID,
	  'SYSTEM', 
	  'SYSTEM',
	  SYSDATE, 
	  SYSDATE
FROM 
	ORDERS 	
WHERE 
	DOMAIN_ID = :domainId
  	AND BATCH_ID = :batchId