package cn.donting.javelin.log;


public class LoggerConfig {

    public static void load(){
//        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        //启用彩色日志,抄的sprinboot  looggin
        AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS);
//        try {
//            URL resource = Thread.currentThread().getContextClassLoader().getResource("logging.properties");
//            InputStream inputStream = resource.openStream();
//            LogManager.getLogManager().readConfiguration(inputStream);
//        }
//        catch (Exception ex){
//            throw new RuntimeException(ex);
//        }
    }
}
