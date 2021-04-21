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
            // 创建DefaultAcsClient实例并初始化
            DefaultProfile profile = DefaultProfile.getProfile(
                    "cn-hangzhou",          // 地域ID
                    accessKey,      // RAM账号的AccessKey ID
                    secretKey); // RAM账号AccessKey Secret

            IAcsClient client = new DefaultAcsClient(profile);

            UploadUserAppToMsaRequest uploadMsaApp = new UploadUserAppToMsaRequest();
            uploadMsaApp.setAppId(appId);
            uploadMsaApp.setFileUrl(url);
            uploadMsaApp.setTenantId(tenantIdCode);
            uploadMsaApp.setWorkspaceId(workspace);

            listener.getLogger().println(prefix + "开始发起对[" + url + "]的加固");

            UploadUserAppToMsaResponse uploadResponse = client.getAcsResponse(uploadMsaApp);
            Long uploadTaskId = uploadResponse.getResultContent().getData().getId();

            /**
             * uploadStatus 取值为
             * -1 失败
             * 0 处理中
             * 1 上传成功
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
                    //TODO 处理上传不成功的问题，比如试用过期等
                    listener.getLogger().println(prefix + "上传不成功,code:" + queryMsaAppResponse.getResultContent().getCode() + " message:" + queryMsaAppResponse.getResultContent().getMessage());
                    return "";
                }

                uploadStatus = queryMsaAppResponse.getResultContent().getData().getStatus();
                taskId = queryMsaAppResponse.getResultContent().getData().getEnhanceTaskId();

                Thread.sleep(1000L);
            } while (uploadStatus == 0L);

            if (uploadStatus == -1) {
                //TODO 处理上传失败的场景
                listener.getLogger().println(prefix + "mPaaS无法完成上传任务");
                return "";
            }

            /**
             * 如果上传成功了，那么会返回加固用的任务id，使用任务id启动加固
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
                // 启动加固失败
                listener.getLogger().println(prefix + "提交加固任务失败,code:" + enhanceResponse.getResultContent().getCode() +" message:" + enhanceResponse.getResultContent().getMessage());
                return "";
            }

            /**
             * 加固状态
             * 0 未开始;
             * 1 已提交任务;
             * 2 加固中;
             * 3 加固成功;
             * 4 加固失败;
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
                    listener.getLogger().println(prefix + "正在加固");
                }
                Thread.sleep(2000L);
            } while (enhanceStatus == 0L || enhanceStatus == 1L || enhanceStatus == 2);

            if (enhanceStatus == 4) {
                //TODO 处理加固失败的逻辑
                listener.getLogger().println(prefix + "加固失败");
                return "";
            }

            GetUserAppDonwloadUrlInMsaRequest downloadRequest = new GetUserAppDonwloadUrlInMsaRequest();
            downloadRequest.setAppId(appId);
            downloadRequest.setTenantId(tenantIdCode);
            downloadRequest.setWorkspaceId(workspace);
            downloadRequest.setId(taskId);

            GetUserAppDonwloadUrlInMsaResponse downloadResponse = client.getAcsResponse(downloadRequest);
            // 拿到 apk 下载地址
            String url = downloadResponse.getResultContent().getData().getUrl();
            listener.getLogger().println(prefix + "加固完成，url是" + url);
            return  url;
        }
    }
}
