UPDATE ORDER_PREPROCESSES
SET    EQUIP_CD = NULL,
       EQUIP_NM = NULL
WHERE  DOMAIN_ID = :domainId
AND    BATCH_ID  = :batchId
