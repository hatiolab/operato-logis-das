INSERT INTO JOB_INSTANCES (
       ID,
       BATCH_ID,
       JOB_DATE,
       JOB_SEQ,
       JOB_TYPE,
       COM_CD,
       EQUIP_TYPE,
       EQUIP_CD,
       EQUIP_NM,
       SUB_EQUIP_CD,
       SHOP_CD,
       SHOP_NM,
       ORDER_NO,
       SKU_CD,
       SKU_NM,
       BOX_TYPE_CD,
       INVOICE_ID,
       BOX_IN_QTY,
       PICK_QTY,
       PICKING_QTY,
       PICKED_QTY,
       ORDER_TYPE,
       DOMAIN_ID,
       STAGE_CD,
       STATUS,
       CREATOR_ID,
       UPDATER_ID,
       CREATED_AT,
       UPDATED_AT
)
SELECT   
		 uuid_generate_v4()::text ID,
         BATCH_ID,
         JOB_DATE,
         JOB_SEQ::INTEGER,
         JOB_TYPE,
         COM_CD,
         EQUIP_TYPE,
         EQUIP_CD,
         EQUIP_NM,
         SUB_EQUIP_CD,
         MAX(SHOP_CD) AS SHOP_CD,
         MAX(SHOP_NM) AS SHOP_NM,
         MAX(ORDER_NO) AS ORDER_NO,
         SKU_CD,
         SKU_NM,
         BOX_TYPE_CD,
         INVOICE_ID,
         BOX_IN_QTY,
       	 SUM(ORDER_QTY) AS PICK_QTY,
       	 0 AS PICKING_QTY,
       	 0 AS PICKED_QTY,
         ORDER_TYPE,
         DOMAIN_ID,
         STAGE_CD,
         'W',
         'system',
         'system',
         now(),
         now()
FROM     
		 ORDERS
WHERE    
		 DOMAIN_ID = :domainId
		 AND BATCH_ID  = :batchId
GROUP BY 
		 BATCH_ID, 
		 JOB_DATE, 
		 JOB_SEQ,
         JOB_TYPE,
         COM_CD,
         EQUIP_TYPE,
         EQUIP_CD,
         EQUIP_NM,
         SUB_EQUIP_CD,
         SKU_CD,
         SKU_NM,
         BOX_TYPE_CD,
         INVOICE_ID,
         BOX_IN_QTY,
         ORDER_TYPE,
         DOMAIN_ID,
         STAGE_CD