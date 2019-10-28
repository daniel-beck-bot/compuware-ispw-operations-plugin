package com.compuware.ispw.git;

import java.io.IOException;
import java.io.PrintStream;
import org.apache.commons.lang.StringUtils;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.compuware.ispw.restapi.util.RestApiUtils;
import com.compuware.jenkins.common.configuration.CpwrGlobalConfiguration;
import com.compuware.jenkins.common.configuration.HostConnection;
import com.compuware.jenkins.common.utils.ArgumentUtils;
import com.compuware.jenkins.common.utils.CommonConstants;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.util.ArgumentListBuilder;

/**
 * 
 * A reusable CLI executor for both freestyle and pipeline Jenkins build
 * 
 * @author Sam Zhou
 *
 */
public class CliExecutor
{
	private PrintStream logger;
	private Run<?, ?> run;
	private EnvVars envVars;
	private Launcher launcher;

	private CpwrGlobalConfiguration globalConfig;

	private String targetFolder;
	private String topazCliWorkspace;
	private String jenkinsJobWorkspacePath;
	private String cliScriptFileRemote;
	private FilePath workDir;

	public CliExecutor(PrintStream logger, Run<?, ?> run, Launcher launcher, EnvVars envVars, String jenkinsJobWorkspacePath,
			String topazCliWorkspace, CpwrGlobalConfiguration globalConfig, String cliScriptFileRemote, FilePath workDir)

	{
		this.logger = logger;
		this.run = run;
		this.envVars = envVars;
		this.launcher = launcher;
		this.targetFolder = run.getRootDir().getAbsolutePath();
		this.globalConfig = globalConfig;

		this.jenkinsJobWorkspacePath = jenkinsJobWorkspacePath;
		this.topazCliWorkspace = topazCliWorkspace;

		this.cliScriptFileRemote = cliScriptFileRemote;

		this.workDir = workDir;
	}


	public boolean execute(String connectionId, String credentialsId, String runtimeConfig,
			String stream, String app, String ispwLevel, String containerPref, String containerDesc, 
			String gitRepoUrl, String gitCredentialsId, String ref, String refId,
			String fromHash, String toHash) throws InterruptedException, IOException
	{
		
		String host;
		String port;
		
		HostConnection connection = globalConfig.getHostConnection(connectionId);
		if (connection != null)
		{
			host = ArgumentUtils.escapeForScript(connection.getHost());
			port = ArgumentUtils.escapeForScript(connection.getPort());
		}
		else
		{
			throw new AbortException("Unable to connect to the host connection."); //$NON-NLS-1$
		}
	
		String protocol = connection.getProtocol();
		String codePage = connection.getCodePage();
		String timeout = ArgumentUtils.escapeForScript(connection.getTimeout());

		StandardUsernamePasswordCredentials credentials = globalConfig.getLoginInformation(run.getParent(), credentialsId);
		String userId = ArgumentUtils.escapeForScript(credentials.getUsername());
		String password = ArgumentUtils.escapeForScript(credentials.getPassword().getPlainText());
		if (RestApiUtils.isIspwDebugMode())
		{
			logger.println("host=" + host + ", port=" + port + ", protocol=" + protocol + ", codePage=" + codePage
					+ ", timeout=" + timeout + ", userId=" + userId + ", password=" + password + ", containerPref="
					+ containerPref + ", containerDesc=" + containerDesc);
		}

		StandardUsernamePasswordCredentials gitCredentials = globalConfig.getLoginInformation(run.getParent(),
				gitCredentialsId);
		String gitUserId = ArgumentUtils.escapeForScript(gitCredentials.getUsername());
		String gitPassword = ArgumentUtils.escapeForScript(gitCredentials.getPassword().getPlainText());
		if (RestApiUtils.isIspwDebugMode())
		{
			logger.println("gitRepoUrl=" + gitRepoUrl + ", gitUserId=" + gitUserId + ", gitPassword=" + gitPassword);
		}

		ArgumentListBuilder args = new ArgumentListBuilder();
		// build the list of arguments to pass to the CLI

		args.add(cliScriptFileRemote);

		// operation
		args.add(GitToIspwConstants.ISPW_OPERATION_PARAM, "syncGitToIspw");

		// host connection
		args.add(CommonConstants.HOST_PARM, host);
		args.add(CommonConstants.PORT_PARM, port);
		args.add(CommonConstants.USERID_PARM, userId);
		args.add(CommonConstants.PW_PARM);
		args.add(password, true);

		if (StringUtils.isNotBlank(protocol))
		{
			args.add(CommonConstants.PROTOCOL_PARM, protocol);
		}

		args.add(CommonConstants.CODE_PAGE_PARM, codePage);
		args.add(CommonConstants.TIMEOUT_PARM, timeout);
		args.add(CommonConstants.TARGET_FOLDER_PARM, targetFolder);
		args.add(CommonConstants.DATA_PARM, topazCliWorkspace);
		
		if (StringUtils.isNotBlank(runtimeConfig))
		{
			args.add(GitToIspwConstants.ISPW_SERVER_CONFIG_PARAM, runtimeConfig);
		}

		// ispw
		args.add(GitToIspwConstants.ISPW_SERVER_STREAM_PARAM, stream);
		args.add(GitToIspwConstants.ISPW_SERVER_APP_PARAM, app);
		args.add(GitToIspwConstants.ISPW_SERVER_CHECKOUT_LEV_PARAM, ispwLevel);
		
		if (StringUtils.isNotBlank(containerPref))
		{
			args.add(GitToIspwConstants.CONTAINER_CREATION_PREF_ARG_PARAM, StringUtils.trimToEmpty(containerPref));
		}

		if (StringUtils.isNotBlank(containerDesc))
		{
			args.add(GitToIspwConstants.CONTAINER_DESCRIPTION_ARG_PARAM, StringUtils.trimToEmpty(containerDesc));
		}
		
		// git
		args.add(GitToIspwConstants.GIT_USERID_PARAM, gitUserId);
		args.add(GitToIspwConstants.GIT_PW_PARAM);
		args.add(gitPassword, true);
		args.add(GitToIspwConstants.GIT_REPO_URL_PARAM, ArgumentUtils.escapeForScript(gitRepoUrl));
		args.add(GitToIspwConstants.GIT_REF_PARAM, ref);
		args.add(GitToIspwConstants.GIT_FROM_HASH_PARAM, fromHash);
		args.add(GitToIspwConstants.GIT_HASH_PARAM, toHash);
		args.add(GitToIspwConstants.JENKINS_WORKSPACE_PATH_ARG_PARAM, jenkinsJobWorkspacePath);

		workDir.mkdirs();
		logger.println("Shell script: " + args.toString());

		// invoke the CLI (execute the batch/shell script)
		int exitValue = launcher.launch().cmds(args).envs(envVars).stdout(logger).pwd(workDir).join();

		String osFile = launcher.isUnix()
				? GitToIspwConstants.SCM_DOWNLOADER_CLI_SH
				: GitToIspwConstants.SCM_DOWNLOADER_CLI_BAT;

		if (exitValue != 0)
		{
			throw new AbortException("Call " + osFile + " exited with value = " + exitValue); //$NON-NLS-1$ //$NON-NLS-2$
		}
		else
		{
			logger.println("Call " + osFile + " exited with value = " + exitValue); //$NON-NLS-1$ //$NON-NLS-2$

			return true;
		}
	}
}