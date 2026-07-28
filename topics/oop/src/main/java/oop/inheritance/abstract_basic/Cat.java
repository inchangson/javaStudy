package oop.inheritance.abstract_basic;

public class Cat extends Pet {
    @Override
    public void bark() {
        System.out.println("meow");
    }
    @Override
    public void eat(){
        System.out.println("안 먹어");
    }
}
