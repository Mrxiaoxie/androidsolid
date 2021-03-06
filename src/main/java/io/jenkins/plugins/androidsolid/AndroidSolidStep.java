package io.jenkins.plugins.androidsolid;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.mpaas.model.v20201028.*;
import com.aliyuncs.profile.DefaultProfile;
import com.google.common.collect.ImmutableSet;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import org.jenkinsci.plugins.workflow.steps.*;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Set;

public class AndroidSolidStep extends Step {

    private static final String prefix = "andriod solid Plugin: ";

    private String accessKey;

    private String secretKey;

    private String appId;

    private String tenantIdCode;

    private String workspace;

    private String url;

    @DataBoundConstructor
    public AndroidSolidStep(String accessKey,String secretKey,String appId, String tenantIdCode, String workspace, String url){
        this.accessKey = accessKey;
        this.secretKey =secretKey;
        this.appId = appId;
        this.tenantIdCode = tenantIdCode;
        this.workspace = workspace;
        this.url = url;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(this,accessKey,secretKey,appId,tenantIdCode,workspace,url,context);
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(FilePath.class, Run.class,TaskListener.class, EnvVars.class);
        }

        @Override
        public String getFunctionName() {
            return "androidsolid";
        }
    }

    public static class Execution extends SynchronousStepExecution<String>{

        private static long serialVersionUID = 6L;

        private transient final String accessKey;

        private transient final String secretKey;

        private transient final String appId;

        private transient final String tenantIdCode;

        private transient final String workspace;

        private transient final String url;

        private transient final TaskListener listener;

        private transient final Launcher launcher;

        private final AndroidSolidStep step;



        Execution(AndroidSolidStep step,String accessKey,String secretKey, String appId, String tenantIdCode, String workspace, String url, StepContext stepContext) throws Exception{
            super(stepContext);
            this.step = step;
            this.accessKey = accessKey;
            this.secretKey =secretKey;
            this.appId = appId;
            this.tenantIdCode = tenantIdCode;
            this.workspace = workspace;
            this.url = url;

            listener = getContext().get(TaskListener.class);
            launcher = getContext().get(Launcher.class);

        }

        @Override
        protected String run() throws Exception {
            DefaultProfile.addEndpoint("cn-hangzhou", "mpaas", "mpaas.cn-hangzhou.aliyuncs.com");
            // ??????DefaultAcsClient??????????????????
            DefaultProfile profile = DefaultProfile.getProfile(
                    "cn-hangzhou",          // ??????ID
                    accessKey,      // RAM?????????AccessKey ID
                    secretKey); // RAM??????AccessKey Secret

            IAcsClient client = new DefaultAcsClient(profile);

            UploadUserAppToMsaRequest uploadMsaApp = new UploadUserAppToMsaRequest();
            uploadMsaApp.setAppId(appId);
            uploadMsaApp.setFileUrl(url);
            uploadMsaApp.setTenantId(tenantIdCode);
            uploadMsaApp.setWorkspaceId(workspace);

            listener.getLogger().println(prefix + "???????????????[" + url + "]?????????");

            UploadUserAppToMsaResponse uploadResponse = client.getAcsResponse(uploadMsaApp);
            Long uploadTaskId = uploadResponse.getResultContent().getData().getId();

            /**
             * uploadStatus ?????????
             * -1 ??????
             * 0 ?????????
             * 1 ????????????
             */
            long uploadStatus = 0L;
            long taskId = -1;
            do {
                GetUserAppUploadProcessInMsaRequest queryMsaAppRequest = new GetUserAppUploadProcessInMsaRequest();
                queryMsaAppRequest.setAppId(appId);
                queryMsaAppRequest.setTenantId(tenantIdCode);
                queryMsaAppRequest.setWorkspaceId(workspace);
                queryMsaAppRequest.setId(uploadTaskId);

                GetUserAppUploadProcessInMsaResponse queryMsaAppResponse = client.getAcsResponse(queryMsaAppRequest);

                boolean uploadResult = queryMsaAppResponse.getResultContent().getSuccess();

                if (!uploadResult) {
                    //TODO ??????????????????????????????????????????????????????
                    listener.getLogger().println(prefix + "???????????????,code:" + queryMsaAppResponse.getResultContent().getCode() + " message:" + queryMsaAppResponse.getResultContent().getMessage());
                    return "";
                }

                uploadStatus = queryMsaAppResponse.getResultContent().getData().getStatus();
                taskId = queryMsaAppResponse.getResultContent().getData().getEnhanceTaskId();

                Thread.sleep(1000L);
            } while (uploadStatus == 0L);

            if (uploadStatus == -1) {
                //TODO ???????????????????????????
                listener.getLogger().println(prefix + "mPaaS????????????????????????");
                return "";
            }

            /**
             * ?????????????????????????????????????????????????????????id???????????????id????????????
             */
            StartUserAppAsyncEnhanceInMsaRequest enhanceRequest = new StartUserAppAsyncEnhanceInMsaRequest();
            enhanceRequest.setAppId(appId);
            enhanceRequest.setWorkspaceId(workspace);
            enhanceRequest.setTenantId(tenantIdCode);
            enhanceRequest.setTaskType("shell");
            enhanceRequest.setId(taskId);

            StartUserAppAsyncEnhanceInMsaResponse enhanceResponse = client.getAcsResponse(enhanceRequest);

            boolean startEnhanceResult = enhanceResponse.getResultContent().getSuccess();

            if (!startEnhanceResult) {
                // ??????????????????
                listener.getLogger().println(prefix + "????????????????????????,code:" + enhanceResponse.getResultContent().getCode() +" message:" + enhanceResponse.getResultContent().getMessage());
                return "";
            }

            /**
             * ????????????
             * 0 ?????????;
             * 1 ???????????????;
             * 2 ?????????;
             * 3 ????????????;
             * 4 ????????????;
             */
            long enhanceStatus = 0L;

            do {

                GetUserAppEnhanceProcessInMsaRequest queryMsaEnhanceRequest = new GetUserAppEnhanceProcessInMsaRequest();
                queryMsaEnhanceRequest.setAppId(appId);
                queryMsaEnhanceRequest.setTenantId(tenantIdCode);
                queryMsaEnhanceRequest.setWorkspaceId(workspace);
                queryMsaEnhanceRequest.setId(taskId);

                GetUserAppEnhanceProcessInMsaResponse queryMsaEnhanceResponse = client.getAcsResponse(queryMsaEnhanceRequest);

                enhanceStatus = queryMsaEnhanceResponse.getResultContent().getData().getStatus();
                if(enhanceStatus == 0L || enhanceStatus == 1L || enhanceStatus == 2){
                    listener.getLogger().println(prefix + "????????????");
                }
                Thread.sleep(2000L);
            } while (enhanceStatus == 0L || enhanceStatus == 1L || enhanceStatus == 2);

            if (enhanceStatus == 4) {
                //TODO ???????????????????????????
                listener.getLogger().println(prefix + "????????????");
                return "";
            }

            GetUserAppDonwloadUrlInMsaRequest downloadRequest = new GetUserAppDonwloadUrlInMsaRequest();
            downloadRequest.setAppId(appId);
            downloadRequest.setTenantId(tenantIdCode);
            downloadRequest.setWorkspaceId(workspace);
            downloadRequest.setId(taskId);

            GetUserAppDonwloadUrlInMsaResponse downloadResponse = client.getAcsResponse(downloadRequest);
            // ?????? apk ????????????
            String url = downloadResponse.getResultContent().getData().getUrl();
            listener.getLogger().println(prefix + "???????????????url???" + url);
            return  url;
        }
    }
}
