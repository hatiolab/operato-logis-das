SELECT
	MAX(JOB.ID) AS ID,
	JOB.DOMAIN_ID,
	JOB.BATCH_ID,
	JOB.STAGE_CD,
	JOB.JOB_TYPE,
	JOB.INPUT_SEQ,
	CELL.IND_CD,
	JOB.SUB_EQUIP_CD,
	JOB.COLOR_CD,
	ROUTER.GW_NM AS GW_PATH,
	CELL.SIDE_CD,
	CELL.STATION_CD,
	JOB.COM_CD,
	JOB.SKU_CD,
	JOB.SKU_NM,
	JOB.CLASS_CD,
	JOB.BOX_IN_QTY,
	'F' AS STATUS,
	SUM(JOB.PICKED_QTY) AS PICK_QTY,
	SUM(JOB.PICKED_QTY) AS PICKING_QTY,
	SUM(JOB.PICKED_QTY) AS PICKED_QTY
FROM
	JOB_INSTANCES JOB
	INNER JOIN CELLS CELL 
		ON JOB.DOMAIN_ID = CELL.DOMAIN_ID 
			AND JOB.EQUIP_TYPE = CELL.EQUIP_TYPE 
			AND JOB.EQUIP_CD = CELL.EQUIP_CD 
			AND JOB.SUB_EQUIP_CD = CELL.CELL_CD
	INNER JOIN SKU SKU 
		ON JOB.COM_CD = SKU.COM_CD 
			AND SKU.SKU_CD = JOB.SKU_CD
	LEFT OUTER JOIN INDICATORS IND 
		ON CELL.DOMAIN_ID = IND.DOMAIN_ID 
			AND CELL.IND_CD = IND.IND_CD
	LEFT OUTER JOIN GATEWAYS ROUTER 
		ON IND.DOMAIN_ID = ROUTER.DOMAIN_ID 
			AND IND.GW_CD = ROUTER.GW_CD
WHERE
	JOB.DOMAIN_ID = :domainId
	AND CELL.ACTIVE_FLAG = true
	#if($batchId)
	AND JOB.BATCH_ID = :batchId
	#end
	#if($inputSeq)
	AND JOB.INPUT_SEQ = :inputSeq
	#end
	#if($comCd)
	AND JOB.COM_CD = :comCd
	#end
	#if($status)
	AND JOB.STATUS = :status
	#end
	#if($statuses)
	AND JOB.STATUS IN (:statuses)
	#end
	#if($classCd)
	AND JOB.CLASS_CD = :classCd
	#end
	#if($skuCd)
	AND JOB.SKU_CD = :skuCd
	#end
	#if($equipType)
	AND CELL.EQUIP_TYPE = :equipType
	#end
	#if($equipCd)
	AND CELL.EQUIP_CD = :equipCd
	#end
	#if($stationCd)
	AND CELL.STATION_CD = :stationCd
	#end
	#if($equipZoneCd)
	AND CELL.EQUIP_ZONE_CD = :equipZoneCd
	#end
	#if($sideCd)
	AND CELL.SIDE_CD = :sideCd
	#end
	#if($indCd)
	AND CELL.IND_CD = :indCd
	#end
	#if($gwCd)
	AND ROUTER.GW_CD = :gwCd
	#end
	#if($pickingQty)
	AND JOB.PICKING_QTY >= :pickingQty
	#end
GROUP BY
	JOB.DOMAIN_ID, JOB.BATCH_ID, JOB.STAGE_CD, JOB.JOB_TYPE, JOB.INPUT_SEQ,
	ROUTER.GW_NM, CELL.IND_CD, JOB.SUB_EQUIP_CD, JOB.COLOR_CD, CELL.SIDE_CD, CELL.STATION_CD,
	JOB.COM_CD, JOB.SKU_CD, JOB.SKU_NM, JOB.CLASS_CD, JOB.BOX_IN_QTY
ORDER BY
	ROUTER.GW_NM ASC, CELL.STATION_CD ASC, JOB.SUB_EQUIP_CD ASC