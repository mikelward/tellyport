package tv.tellyport.shizuku;

interface IForceStopService {
    boolean forceStopPackage(String packageName) = 1;
    void destroy() = 16777114;
}
