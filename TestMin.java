import java.util.*;

public class TestMin {
    public static void main(String[] args) {
        List<String> list = new ArrayList<>();
        list.add("5ec2131ef5f41ba2");
        list.add("28f10305");
        list.add("670868de");
        list.add("5ec2131e");
        
        System.out.println("Min is: " + Collections.min(list));
    }
}
