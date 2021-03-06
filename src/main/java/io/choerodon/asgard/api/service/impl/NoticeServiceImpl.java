package io.choerodon.asgard.api.service.impl;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import io.choerodon.asgard.api.dto.RegistrantInfoDTO;
import io.choerodon.asgard.api.service.NoticeService;
import io.choerodon.asgard.domain.QuartzTask;
import io.choerodon.asgard.domain.QuartzTaskMember;
import io.choerodon.asgard.domain.SagaInstance;
import io.choerodon.asgard.domain.SagaTaskInstance;
import io.choerodon.asgard.infra.enums.BusinessTypeCode;
import io.choerodon.asgard.infra.enums.MemberType;
import io.choerodon.asgard.infra.feign.IamFeignClient;
import io.choerodon.asgard.infra.feign.NotifyFeignClient;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.core.notify.NoticeSendDTO;

/**
 * @author dengyouquan
 **/
@Service
public class NoticeServiceImpl implements NoticeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NoticeServiceImpl.class);

    private static final String JOB_NAME = "jobName";
    private static final String JOB_STATUS = "jobStatus";

    private NotifyFeignClient notifyFeignClient;

    private IamFeignClient iamFeignClient;

    public static final String REGISTER_ABNORMAL_TEMPLATE = "registerOrganization-abnormal";

    public NoticeServiceImpl(NotifyFeignClient notifyFeignClient, IamFeignClient iamFeignClient) {
        this.notifyFeignClient = notifyFeignClient;
        this.iamFeignClient = iamFeignClient;
    }

    @Override
    @Async("notify-executor")
    public void sendNotice(QuartzTask quartzTask, List<QuartzTaskMember> noticeMember, String jobStatus) {
        try {
            //发送通知失败不需要回滚
            NoticeSendDTO noticeSendDTO = getNoticeSendDTO(noticeMember, quartzTask.getName(), quartzTask.getLevel(), quartzTask.getSourceId(), jobStatus);
            notifyFeignClient.postNotice(noticeSendDTO);
        } catch (CommonException e) {
            LOGGER.info("schedule job send notice fail!", e);
        }
    }

    @Override
    public void sendSagaFailNotice(SagaInstance instance) {
        //捕获异常，以免影响saga一致性
        try {
            NoticeSendDTO noticeSendDTO = new NoticeSendDTO();
            noticeSendDTO.setCode("sagaInstanceFail");
            noticeSendDTO.setSourceId(0L);
            NoticeSendDTO.User user = new NoticeSendDTO.User();
            user.setId(instance.getCreatedBy());
            List<NoticeSendDTO.User> users = new ArrayList<>();
            users.add(user);
            noticeSendDTO.setTargetUsers(users);
            Map<String, Object> params = new HashMap<>();
            params.put("sagaInstanceId", instance.getId());
            params.put("sagaCode", instance.getSagaCode());
            params.put("level", instance.getLevel());
            noticeSendDTO.setParams(params);
            notifyFeignClient.postNotice(noticeSendDTO);
        } catch (Exception e) {
            LOGGER.info("saga instance fail send notice fail, msg: {}", e.getMessage());
        }
    }

    private NoticeSendDTO getNoticeSendDTO(final List<QuartzTaskMember> notifyMembers, final String jobName, final String level, final Long sourceId, final String jobStatus) {
        NoticeSendDTO noticeSendDTO = new NoticeSendDTO();
        noticeSendDTO.setSourceId(sourceId);
        noticeSendDTO.setCode(BusinessTypeCode.getValueByLevel(level).value());
        List<NoticeSendDTO.User> users = getNeedSendNoticeUsers(notifyMembers, level, sourceId);
        noticeSendDTO.setTargetUsers(users);
        Map<String, Object> params = new HashMap<>();
        params.put(JOB_NAME, jobName);
        params.put(JOB_STATUS, jobStatus);
        noticeSendDTO.setParams(params);
        return noticeSendDTO;
    }

    /**
     * 得到需要发送通知的所有用户
     *
     * @param notifyMembers
     * @param level
     * @param sourceId
     * @return
     */
    private List<NoticeSendDTO.User> getNeedSendNoticeUsers(final List<QuartzTaskMember> notifyMembers, final String level, final Long sourceId) {
        Set<NoticeSendDTO.User> users = new HashSet<>();
        if (notifyMembers == null) return new ArrayList<>(users);
        for (QuartzTaskMember notifyMember : notifyMembers) {
            if (MemberType.ASSIGNER.value().equals(notifyMember.getMemberType())
                    || MemberType.CREATOR.value().equals(notifyMember.getMemberType())) {
                NoticeSendDTO.User user = new NoticeSendDTO.User();
                user.setId(notifyMember.getMemberId());
                users.add(user);
            }
            if (MemberType.ROLE.value().equals(notifyMember.getMemberType())) {
                users.addAll(getAdministratorUsers(level, sourceId, notifyMember.getMemberId()));
            }
        }
        //去重
        return new ArrayList<>(users);
    }

    /**
     * 得到组织/项目/全局层对应管理员的所有用户
     *
     * @param level
     * @param sourceId
     * @param roleId
     * @return
     */
    private List<NoticeSendDTO.User> getAdministratorUsers(String level, Long sourceId, Long roleId) {
        List<NoticeSendDTO.User> users = new ArrayList<>();
        if (ResourceLevel.SITE.value().equals(level)) {
            users = iamFeignClient.pagingQueryUsersByRoleIdOnSiteLevel(roleId, false).getBody();
        }
        if (ResourceLevel.ORGANIZATION.value().equals(level)) {
            users = iamFeignClient.pagingQueryUsersByRoleIdOnOrganizationLevel(roleId, sourceId, false).getBody();
        }
        if (ResourceLevel.PROJECT.value().equals(level)) {
            users = iamFeignClient.pagingQueryUsersByRoleIdOnProjectLevel(roleId, sourceId, false).getBody();
        }
        return users;
    }


    @Override
    public void registerOrgFailNotice(SagaTaskInstance sagaTaskInstance, SagaInstance sagaInstance) {
        //feign查询负责人及其组织
        RegistrantInfoDTO registrantInfoDTO = iamFeignClient.queryRegistrantAndAdminId(sagaInstance.getRefId()).getBody();
        LOGGER.info("register failed,ref id:{},registrant info：{}", sagaInstance.getRefId(), registrantInfoDTO);

        List<Long> adminId = new ArrayList<>();
        adminId.add(registrantInfoDTO.getAdminId());

        Map<String, Object> abnormalMap = new HashMap<>();
        abnormalMap.put("registrant", registrantInfoDTO.getRealName());
        abnormalMap.put("organizationId", registrantInfoDTO.getOrganizationId());
        abnormalMap.put("organizationName", registrantInfoDTO.getOrganizationName());
        abnormalMap.put("sagaInstanceId", sagaInstance.getSagaCode() + ":" + sagaInstance.getId());
        abnormalMap.put("sagaTaskInstanceId", sagaTaskInstance.getTaskCode() + ":" + sagaTaskInstance.getId());
        sendNoticeAtSite(REGISTER_ABNORMAL_TEMPLATE, adminId, abnormalMap);
    }

    private void sendNoticeAtSite(String code, List<Long> userIds, Map<String, Object> params) {
        NoticeSendDTO noticeSendDTO = new NoticeSendDTO();
        noticeSendDTO.setCode(code);
        noticeSendDTO.setSourceId(0L);
        List<NoticeSendDTO.User> users = new ArrayList<>();
        userIds.forEach(id -> {
            NoticeSendDTO.User user = new NoticeSendDTO.User();
            user.setId(id);
            users.add(user);
        });
        noticeSendDTO.setTargetUsers(users);
        noticeSendDTO.setParams(params);
        notifyFeignClient.postNotice(noticeSendDTO);
    }
}
