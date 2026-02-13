package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    private static final ThreadLocal<Long> tl = new ThreadLocal<>();

    private static final ThreadLocal<UserDTO> userDTOThreadLocal = new ThreadLocal<>();
    public static void saveUserId(Long userId){
        tl.set(userId);
    }

    public static Long getUserId(){
        return tl.get();
    }

    public static UserDTO getUserDTO(){
        return userDTOThreadLocal.get();
    }
    public static void saveUserDTO(UserDTO userDTO){
        userDTOThreadLocal.set(userDTO);
    }
    public static void remove(){
        tl.remove();
        userDTOThreadLocal.remove();
    }
}
