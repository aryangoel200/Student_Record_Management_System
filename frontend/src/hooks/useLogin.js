import { useState } from 'react';
import { useDispatch } from 'react-redux';
import { personLogin } from '../Store/Slices/personSlice';
import axiosInstance, { apiErrorMessage, storeTokens } from '../axios';

export const useLogin = () => {
    const [error, setError] = useState(null);
    const [isLoading, setIsLoading] = useState(false);
    const dispatch = useDispatch();

    const login = async (formData) => {
        setIsLoading(true);
        setError(null);

        try {
            // A failed login now rejects, so the success path below only runs
            // when there really is a token. The old version attached a .catch()
            // and then read response.data.token regardless, which threw on
            // every wrong password.
            const { data } = await axiosInstance.post('Auth/login', formData);
            storeTokens(data.token);
            dispatch(personLogin(data.user));
            return true;
        } catch (err) {
            setError(apiErrorMessage(err, 'Username or password is not valid.'));
            return false;
        } finally {
            setIsLoading(false);
        }
    };

    return { login, isLoading, error };
};
