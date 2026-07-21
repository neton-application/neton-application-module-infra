package init

import neton.core.annotations.Module

/** infra 模块声明锚点（MANIFEST-P3）。@Logic: ConfigLogic / FileLogic / JobLogic;
 *  runtime: init.InfraRuntimeBootstrap (Table 注册 + FileUploadLogic + 日志写入器);
 *  migrations + 路由由 KSP manifest。 */
@Module(dependsOn = ["system"], migrations = true)
object InfraModule
