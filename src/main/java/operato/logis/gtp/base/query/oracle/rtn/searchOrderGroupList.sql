SELECT DISTINCT(CLASS_CD) AS CLASS_CD 
FROM ORDERS 
WHERE DOMAIN_ID = :DOMAINID 
  AND BATCH_ID = :BATCHID 
  AND (CLASS_CD IS NOT NULL AND LENGTH(CLASS_CD) > 0) 
ORDER BY CLASS_CD;