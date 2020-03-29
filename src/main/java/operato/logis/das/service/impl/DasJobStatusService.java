package operato.logis.das.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import operato.logis.das.query.store.DasQueryStore;
import xyz.anythings.base.LogisConstants;
import xyz.anythings.base.entity.JobBatch;
import xyz.anythings.base.entity.JobInput;
import xyz.anythings.base.entity.JobInstance;
import xyz.anythings.base.service.impl.AbstractJobStatusService;
import xyz.anythings.sys.util.AnyEntityUtil;
import xyz.anythings.sys.util.AnyOrmUtil;
import xyz.elidom.dbist.dml.Page;
import xyz.elidom.dbist.dml.Query;
import xyz.elidom.sys.util.ValueUtil;

/**
 * 출고 작업 상태 서비스
 * 
 * @author shortstop
 */
@Component("dasJobStatusService")
public class DasJobStatusService extends AbstractJobStatusService {

	/**
	 * 출고 배치 관련 쿼리 스토어 
	 */
	@Autowired
	protected DasQueryStore dasQueryStore;
	
	@Override
	public List<JobInput> searchInputList(JobBatch batch, String equipCd, String stationCd, String selectedInputId) {
		// 태블릿의 현재 투입 정보 기준으로 2, 1 (next), 0 (current), -1 (previous) 정보를 표시
		Long domainId = batch.getDomainId();
		JobInput currentInput = AnyEntityUtil.findEntityBy(domainId, true, JobInput.class, "*", "id", selectedInputId);
		int inputSeq = currentInput.getInputSeq();
		
		// 현재 투입 시퀀스가 1이면 현재 투입 정보 하나만 리턴 
		if(inputSeq < 2) {
			return ValueUtil.toList(currentInput);
		}

		Query condition = AnyOrmUtil.newConditionForExecution(domainId);
		condition.addFilter("batchId", batch.getId());
		
		if(ValueUtil.isNotEmpty(equipCd)) {
			condition.addFilter("equipCd", equipCd);
		}
		
		if(ValueUtil.isNotEmpty(stationCd)) {
			condition.addFilter("stationCd", stationCd);
		}
		
		// 현재 투입 시퀀스가 4보다 작거나 같으면 4이하 모두 조회하여 리턴  
		if(inputSeq >= 2 && inputSeq <= 4) {
			condition.addFilter("inputSeq", "<=", inputSeq);			
		// 그렇지 않으면 현재 투입 시퀀스 -1 ~ 현재 투입 시퀀스 + 2까지 조회하여 리턴
		} else {
			condition.addFilter("inputSeq", "between", ValueUtil.toList(inputSeq - 1, inputSeq + 2));
		}
		
		return this.queryManager.selectList(JobInput.class, condition);
	}

	@Override
	public Page<JobInput> paginateInputList(JobBatch batch, String equipCd, String status, int page, int limit) {
		Query condition = AnyOrmUtil.newConditionForExecution(batch.getDomainId(), page, limit);
		condition.addFilter("batchId", batch.getId());
		
		if(ValueUtil.isNotEmpty(equipCd)) {
			condition.addFilter("equipCd", equipCd);
		}
		
		if(ValueUtil.isNotEmpty(status)) {
			condition.addFilter("status", status);
		}
		
		return this.queryManager.selectPage(JobInput.class, condition);
	}

	@Override
	public List<JobInstance> searchInputJobList(JobBatch batch, JobInput input, String stationCd) {
		String sql = this.dasQueryStore.getSearchPickingJobListQuery();
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId,inputSeq,stationCd", batch.getDomainId(), batch.getId(), input.getInputSeq(), stationCd);
		return this.queryManager.selectListBySql(sql, params, JobInstance.class, 0, 0);
	}

	@Override
	public List<JobInstance> searchInputJobList(JobBatch batch, Map<String, Object> condition) {
		this.addBatchConditions(batch, condition);
		return this.queryManager.selectList(JobInstance.class, condition);
	}

	@Override
	public List<JobInstance> searchPickingJobList(JobBatch batch, String stationCd, String classCd) {
		// 표시기 점등을 위해서 다른 테이블의 데이터도 필요해서 쿼리로 조회
		String sql = this.dasQueryStore.getSearchPickingJobListQuery();
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId,classCd,statuses", batch.getDomainId(), batch.getId(), classCd, LogisConstants.JOB_STATUS_WIPC);
		return this.queryManager.selectListBySql(sql, params, JobInstance.class, 0, 0);
	}

	@Override
	public List<JobInstance> searchPickingJobList(JobBatch batch, Map<String, Object> condition) {
		// 표시기 점등을 위해서 다른 테이블의 데이터도 필요해서 쿼리로 조회
		String sql = this.dasQueryStore.getSearchPickingJobListQuery();
		this.addBatchConditions(batch, condition);
		return this.queryManager.selectListBySql(sql, condition, JobInstance.class, 0, 0);
	}

}