package oop.inheritance;

import oop.inheritance.abstract_basic.Cat;
import oop.inheritance.abstract_basic.Dog;

public class Main {
    public static void main(){
        Dog happy = new Dog();
        happy.bark();
        Cat jennifer = new Cat();
        jennifer.bark();
        jennifer.eat();
    }
}
