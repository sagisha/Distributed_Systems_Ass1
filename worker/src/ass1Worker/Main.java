package ass1Worker;

public class Main {
    public static void main(String[] args )throws Exception {
        Worker worker = new Worker();
        while (true)
            worker.work();
    }
}