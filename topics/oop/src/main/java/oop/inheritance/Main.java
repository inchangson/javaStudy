package oop.inheritance;

import oop.inheritance.abstract_basic.Cat;
import oop.inheritance.abstract_basic.Dog;

public class Main {
    public static void main(String[] args) {
        run();
    }

    public static void run() {
        Dog happy = new Dog();
        happy.bark();
        Cat jennifer = new Cat();
        jennifer.bark();
        jennifer.eat();
    }
}
